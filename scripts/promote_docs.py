#!/usr/bin/env python3
"""정본 문서 버전 승격(bookkeeping) 자동화 — 문서 병합 충돌을 줄이기 위한 배치 도구.

문서 병합 충돌은 거의 항상 **모든 승격 PR이 동시에 건드리는 공유 전역 상태 3곳**에서 난다:
  1. SA/PRD 버전 헤더 `| 문서 버전 | vX.Y |` (단일 카운터)
  2. 한 줄짜리 `> 변경 이력 — ...` (모두 끝에 append → tail 충돌)
  3. 경량본 4개의 정본 버전 참조 행

동시 편집이 원인이므로, 이 bookkeeping을 **feature PR에서 빼고** develop 병합이
직렬화되는 시점에 이 스크립트로 한 번에 처리하면 충돌이 사라진다(develop 위 커밋은 순차).

즉:
  - feature PR — 자기 SA/PRD 도메인 '절(내용)'만 수정한다. 버전 헤더·이력·경량본 참조는 손대지 않는다.
  - 승격 — 그 PR이 develop에 merge된 뒤, 오너가 develop에서 이 스크립트를 실행하고 develop에 바로 push한다.
  - push 거부 시(다른 승격이 먼저 오름) — 로컬 승격 커밋을 merge/rebase하지 말고 폐기하고
    최신 develop에서 재실행한다(폐기·재생성). 승격 커밋은 (최신 develop + 항목)의 순수
    함수라 안전하고, pull·merge로 합치면 헤더·이력이 재충돌한다. **구체 git 명령·확인 단계·조건의
    정본은 docs/collaboration/github-rules.md §3 "문서 버전 승격 예외"** 한 곳이다 — 절차를 여러
    곳에 복제하면 한 사본만 바뀌어 어긋나므로(실제로 그랬다), 명령 시퀀스는 §3에서만 관리한다.

사용:
  # 실제 승격: clean tree에서 산출 파일만 stage해 커밋까지 한다(권장).
  python scripts/promote_docs.py --sa "PR #134 리뷰 지적 — 알림 배지 API를 §8-8에 반영했다." --commit
  python scripts/promote_docs.py --prd "반려동물 사진 업로드를 §8에 추가했다." --sa "§8-2·§9-11 신설." --commit
  python scripts/promote_docs.py --sa "..." --dry-run    # 미리보기만(파일 미변경)
  # --commit 없이 실행하면 파일만 편집한다(미리보기·검토용). 이때 `git commit -am`으로 직접
  # 커밋하지 말 것 — 승격과 무관한 tracked 변경까지 섞여 develop에 새거나 유실될 수 있다.

동작(대상별로 SA/PRD 각각):
  1. 정본 헤더에서 현재 버전을 읽어 마이너 +1을 다음 버전으로 삼는다.
  2. 헤더를 다음 버전으로 바꾼다.
  3. `> 변경 이력` 줄 끝에 ` v{다음}: {항목}`을 이어붙인다(항목은 반드시 한 줄 — 개행 거부).
  4. 경량본 4개의 해당 정본 버전 참조를 다음 버전으로 동기한다.
끝나면 scripts/harness_check.py로 정합성을 검증한다(PASS라야 성공).
--commit이면 검증 통과 후 산출 파일만 명시적으로 stage해 커밋한다(clean tree 전제).
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

SA = ROOT / "docs/architecture/DoctorPet-SA.md"
PRD = ROOT / "docs/product/DoctorPet-PRD.md"
LIGHTWEIGHT_DIR = ROOT / "docs/lightweight"

HEADER_RE = re.compile(r"(\| 문서 버전 \| v)(\d+)\.(\d+)( \|)")
CHANGELOG_RE = re.compile(r"^(> 변경 이력 —.*)$", re.MULTILINE)


class PromoteError(RuntimeError):
    pass


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _git(*args: str, capture: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(ROOT), *args],
        capture_output=capture, text=True, encoding="utf-8", errors="replace")


def _current_branch() -> str:
    return _git("rev-parse", "--abbrev-ref", "HEAD", capture=True).stdout.strip()


def _ensure_git_clean() -> None:
    """--commit 전제: git 저장소 + develop 브랜치 + 최신 origin/develop과 동기 + clean 워킹트리.

    브랜치·원격 동기를 확인하지 않으면 feature 브랜치나 뒤처지거나 앞선(unrelated local
    commit이 섞인) develop에서 실행해도 그대로 커밋·push가 진행돼, 승격과 무관한 변경까지
    PR 없이 develop에 반영될 수 있다(리뷰 지적). 산출 파일만 stage하는 것은 새 승격
    커밋 자체의 내용만 보장하고, 이미 존재하는 무관한 로컬 커밋의 혼입은 막지 못한다.
    그래서 fetch로 최신 origin/develop을 받아 HEAD가 정확히 일치하는지까지 확인한 뒤에야
    파일을 쓴다.
    """
    inside = _git("rev-parse", "--is-inside-work-tree", capture=True)
    if inside.returncode != 0 or inside.stdout.strip() != "true":
        raise PromoteError("git 저장소가 아니어서 --commit할 수 없다")

    branch = _current_branch()
    if branch != "develop":
        raise PromoteError(
            f"현재 브랜치가 'develop'이 아니라 '{branch}'다 — 승격 직접 push는 develop에서만 "
            "허용된다(github-rules.md §3). `git checkout develop`으로 전환한 뒤 재실행한다.")

    fetch = _git("fetch", "origin", "develop", capture=True)
    if fetch.returncode != 0:
        raise PromoteError(
            "origin/develop을 fetch하지 못해 원격과의 동기 여부를 확인할 수 없다 — 승격을 "
            "진행할 수 없다.\n" + fetch.stderr.strip())

    head = _git("rev-parse", "HEAD", capture=True).stdout.strip()
    remote_head = _git("rev-parse", "origin/develop", capture=True).stdout.strip()
    if head != remote_head:
        raise PromoteError(
            "로컬 develop이 최신 origin/develop과 다르다 — 이 상태로 커밋·push하면 승격과 "
            "무관한 로컬 커밋까지 develop에 그대로 반영될 위험이 있다.\n"
            f"HEAD={head[:12]}, origin/develop={remote_head[:12]}. 로컬에만 있는 커밋이 "
            "있으면 정리하고, 뒤처졌다면 `git pull --ff-only origin develop`으로 맞춘 뒤 "
            "재실행한다(github-rules.md §3).")

    st = _git("status", "--porcelain", capture=True)
    if st.stdout.strip():
        raise PromoteError(
            "워킹트리에 미커밋 변경이 있다 — 승격은 clean tree에서만 안전하다.\n"
            "먼저 `git stash` 또는 별도 커밋으로 정리한 뒤 재실행한다:\n"
            + st.stdout.rstrip())


def _validate_entry(entry: str, label: str) -> None:
    """변경 이력 항목은 반드시 '한 줄'이고 비어 있지 않아야 한다.

    개행이 섞이면 한 줄이어야 할 `> 변경 이력`이 쪼개지는데, harness_check는
    이력 형식을 검사하지 않아 그대로 PASS할 수 있다(리뷰 지적). 여기서 막는다.
    """
    if "\n" in entry or "\r" in entry:
        raise PromoteError(f"{label}: 변경 이력 항목은 한 줄이어야 한다(개행 불가)")
    if not entry.strip():
        raise PromoteError(f"{label}: 변경 이력 항목이 비어 있다")


def _next_version(text: str, label: str) -> tuple[str, str]:
    """헤더에서 현재 버전을 읽어 (현재, 다음마이너) 문자열을 반환한다."""
    m = HEADER_RE.search(text)
    if not m:
        raise PromoteError(f"{label}: `| 문서 버전 | vX.Y |` 헤더를 찾을 수 없다")
    cur = f"v{m.group(2)}.{m.group(3)}"
    nxt = f"v{m.group(2)}.{int(m.group(3)) + 1}"
    return cur, nxt


def _bump_canonical(path: Path, label: str, entry: str,
                    lightweight_path_token: str) -> tuple[str, str, dict[Path, str]]:
    """정본 헤더·이력을 갱신하고, 경량본 참조까지 동기한 새 텍스트들을 반환한다(파일 미기록)."""
    _validate_entry(entry, label)
    text = _read(path)
    cur, nxt = _next_version(text, label)

    # 1) 헤더
    new_text, n = HEADER_RE.subn(rf"\g<1>{nxt[1:]}\g<4>", text, count=1)
    if n != 1:
        raise PromoteError(f"{label}: 버전 헤더 치환 실패")

    # 2) 변경 이력 append (한 줄 유지 — 형식은 기존 그대로)
    entry = entry.strip()
    if not entry.endswith((".", "다", "음", "함")):
        entry += "."  # 한국어 문장은 마침표로 끝낸다(코드컨벤션)

    def _append(m: re.Match) -> str:
        return f"{m.group(1)} {nxt}: {entry}"

    new_text, n = CHANGELOG_RE.subn(_append, new_text, count=1)
    if n != 1:
        raise PromoteError(f"{label}: `> 변경 이력` 줄을 찾을 수 없다")

    # 3) 경량본 4개의 정본 버전 참조 동기
    ref_re = re.compile(rf"(`{re.escape(lightweight_path_token)}` )v\d+\.\d+")
    lw_updates: dict[Path, str] = {}
    for lw in sorted(LIGHTWEIGHT_DIR.glob("*.md")):
        if lw.name == "README.md":
            continue
        lw_text = _read(lw)
        bumped, cnt = ref_re.subn(rf"\g<1>{nxt}", lw_text)
        if cnt:
            lw_updates[lw] = bumped
    return cur, nxt, {path: new_text, **lw_updates}


def _run_harness() -> int:
    check = ROOT / "scripts/harness_check.py"
    if not check.exists():
        print("경고: scripts/harness_check.py가 없어 검증을 건너뛴다")
        return 0
    # 부모 프로세스의 print()는 파이프로 캡처될 때 완전 버퍼링돼, flush 없이
    # subprocess를 띄우면 자식이 상속한 같은 stdout fd에 먼저 써서 순서가 뒤바뀐다.
    sys.stdout.flush()
    return subprocess.run([sys.executable, str(check)]).returncode


def main() -> int:
    # Windows 콘솔(cp949) 한글 출력 보호. 파이프·리다이렉트로 stdout이 교체돼
    # reconfigure가 없을 수 있으니 있을 때만 호출한다.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description="정본 문서 버전 승격 bookkeeping")
    ap.add_argument("--sa", metavar="ENTRY", help="SA 변경 이력에 추가할 항목(마이너 +1)")
    ap.add_argument("--prd", metavar="ENTRY", help="PRD 변경 이력에 추가할 항목(마이너 +1)")
    ap.add_argument("--dry-run", action="store_true", help="파일을 바꾸지 않고 미리보기만")
    ap.add_argument("--commit", action="store_true",
                    help="clean tree를 강제하고 산출 파일만 stage해 커밋한다")
    args = ap.parse_args()

    if not args.sa and not args.prd:
        ap.error("--sa 또는 --prd 중 하나 이상을 지정한다")
    if args.commit and args.dry_run:
        ap.error("--commit과 --dry-run은 함께 쓸 수 없다")

    pending: dict[Path, str] = {}
    summary: list[str] = []
    bumps: list[str] = []  # 커밋 메시지용: ["SA v1.52", "PRD v3.25"]
    try:
        if args.sa:
            cur, nxt, files = _bump_canonical(
                SA, "SA", args.sa, "docs/architecture/DoctorPet-SA.md")
            pending.update(files)
            summary.append(f"SA {cur} → {nxt} (경량본 {len(files) - 1}개 참조 동기)")
            bumps.append(f"SA {nxt}")
        if args.prd:
            cur, nxt, files = _bump_canonical(
                PRD, "PRD", args.prd, "docs/product/DoctorPet-PRD.md")
            # SA가 이미 경량본을 건드렸을 수 있으니, PRD 변경은 그 결과 위에 얹는다
            for p, t in files.items():
                if p in pending and p != PRD:
                    # 이미 SA로 갱신된 경량본 텍스트에 PRD 참조도 반영
                    ref_re = re.compile(r"(`docs/product/DoctorPet-PRD\.md` )v\d+\.\d+")
                    pending[p] = ref_re.sub(rf"\g<1>{nxt}", pending[p])
                else:
                    pending[p] = t
            summary.append(f"PRD {cur} → {nxt} (경량본 참조 동기)")
            bumps.append(f"PRD {nxt}")
        # --commit이면 파일을 쓰기 '전에' clean tree를 강제한다(무관한 변경 혼입·유실 방지)
        if args.commit and not args.dry_run:
            _ensure_git_clean()
    except PromoteError as e:
        print(f"실패 — {e}")
        return 1

    print("승격 계획:")
    for line in summary:
        print(f"  - {line}")
    print("변경 파일:")
    written = sorted(pending, key=lambda x: x.as_posix())
    for p in written:
        print(f"  - {p.relative_to(ROOT).as_posix()}")

    if args.dry_run:
        print("\n[dry-run] 파일을 바꾸지 않았다.")
        return 0

    for p, t in pending.items():
        p.write_text(t, encoding="utf-8")

    print("\n검증(harness_check):")
    rc = _run_harness()
    if rc != 0:
        if args.commit:
            # --commit은 clean tree in/out이 계약이다(_ensure_git_clean으로 실행 전 clean 보장). 검증 실패 시
            # 방금 쓴 산출 파일을 커밋 전(index) 상태로 되돌려 워킹트리를 다시 clean으로 복구한다 — 안 되돌리면
            # 실패 후 워킹트리에 승격 편집이 남아, 이후 push 거부 복구 절차의 reset --hard가 지울 위험이 생긴다.
            rels = [p.relative_to(ROOT).as_posix() for p in written]
            restore = _git("restore", "--", *rels, capture=True)
            if restore.returncode == 0:
                print("실패 — harness_check가 FAIL. 산출 파일을 되돌려 워킹트리를 clean으로 복구했다. 원인을 확인한다.")
            else:
                print("실패 — harness_check가 FAIL. 산출 파일 복구도 실패했으니 `git restore`로 직접 되돌린다: "
                      + restore.stderr.strip())
        else:
            print("실패 — harness_check가 FAIL. 변경을 되돌리거나(`git restore`) 원인을 확인한다.")
        return 1

    if args.commit:
        rels = [p.relative_to(ROOT).as_posix() for p in written]
        add = _git("add", "--", *rels, capture=True)
        if add.returncode != 0:
            print(f"실패 — git add: {add.stderr.strip()}")
            return 1
        msg = "docs: " + "·".join(bumps) + " 승격"
        commit = _git("commit", "-m", msg, capture=True)
        if commit.returncode != 0:
            print(f"실패 — git commit: {commit.stdout.strip()} {commit.stderr.strip()}")
            return 1
        print(f"\n커밋 완료: {msg} (산출 파일 {len(rels)}개만 stage). `git push`로 올린다.")
    else:
        print("\n파일만 편집했다. 커밋은 --commit으로 실행할 것 "
              "(clean tree 강제·산출 파일만 stage). `git commit -am`은 쓰지 말 것.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
