#!/usr/bin/env python3
"""DoctorPet 하네스 무결성 검사.

검사 범위 (의도적으로 최소로 제한한다 — 검사기가 장애물이 되지 않게):
  1. 하네스·경량 문서의 마크다운 상대 링크가 실제 파일을 가리키는가
  2. rule-source-map.md 표의 정본 경로가 실존하는가
  3. `PRD §X-Y` / `SA §X-Y` / `SA 부록 A·B` 참조가 실제 문서 헤더에 존재하는가
     (하네스 문서뿐 아니라 PRD·SA가 서로를 가리키는 상호 참조도 검사한다)
  4. PR 템플릿(.github/pull_request_template.md)이 github-rules.md의
     "### PR 템플릿" 코드블록과 일치하는가 (정본-사본 동기)
  5. 경량본의 정본 경로·버전 표기가 현재 정본과 일치하는가
  6. 프로즈에 `#Foo`·`#A1`처럼 정의 없는 참조 태그가 새지 않았는가
     (실제 이슈·PR은 `#숫자`, 섹션은 `§N-M`·`부록 A/B`, 파일은 마크다운 링크로만
      표기한다. #뒤에 영문자가 붙은 토큰은 대응 대상이 없는 AI 잔여물로 본다.
      PR #134에서 리뷰 반영 커밋이 흘린 `(#A1·#A2)`가 이 사각지대로 샜다.)

사용: python scripts/harness_check.py   (성공 시 exit 0, 결함 발견 시 exit 1)
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 검사 대상 하네스 문서
HARNESS_DOCS = [
    "AGENTS.md",
    "CLAUDE.md",
    "docs/ai/context-router.md",
    "docs/ai/rule-source-map.md",
    "docs/ai/review-gate.md",
    "docs/ai/implementation-guardrails.md",
    "docs/ai/completion-checklist.md",
    "docs/ai/agent-mistakes.md",
    "docs/ai/remove-ai-slop.md",
    "docs/testing/verification-guide.md",
    ".github/ISSUE_TEMPLATE/feature.md",
]

LIGHTWEIGHT_DIR = ROOT / "docs/lightweight"
LIGHTWEIGHT_DOCS = sorted(
    path.relative_to(ROOT).as_posix()
    for path in LIGHTWEIGHT_DIR.glob("*.md")
)
LIGHTWEIGHT_SUMMARIES = [
    rel for rel in LIGHTWEIGHT_DOCS
    if rel != "docs/lightweight/README.md"
]

# 고도화 델타 레이어 — README.md뿐 아니라 도메인별 델타 문서(결제.md 등)도 전부
# 동적으로 수집한다. 정적으로 하나만 넣으면 새 델타 문서의 깨진 링크·섹션 참조를
# 하네스가 못 잡는다(리뷰 지적).
ENHANCEMENT_DIR = ROOT / "docs/enhancement"
ENHANCEMENT_DOCS = sorted(
    path.relative_to(ROOT).as_posix()
    for path in ENHANCEMENT_DIR.glob("*.md")
)

PRD = ROOT / "docs/product/DoctorPet-PRD.md"
SA = ROOT / "docs/architecture/DoctorPet-SA.md"
CODE_CONVENTION = ROOT / "docs/architecture/DoctorPet-코드컨벤션.md"
POLICY = ROOT / "docs/domain/반려동물병원예약-정책정리본.md"

MD_LINK = re.compile(r"\[[^\]]*\]\(([^)#\s]+)(?:#[^)]*)?\)")
BACKTICK_PATH = re.compile(r"`((?:docs|scripts|\.github)/[^`\s]+\.(?:md|py|yml))`")
SECTION_REF = re.compile(r"(PRD|SA)\s*§(\d+(?:-\d+)?(?:~\d+(?:-\d+)?)?)")
APPENDIX_REF = re.compile(r"SA\s*부록\s*([AB])")

# 프로즈에 새는 '정의 없는 참조 태그' 검출용.
# 코드펜스·인라인코드·마크다운 링크 앵커를 걷어낸 뒤 남은 프로즈에서
# `#` + 영문자로 시작하는 토큰(#A1, #Foo 등)을 찾는다. 실제 이슈·PR은 #숫자라
# 여기 안 걸리고, 섹션(§)·부록·파일 링크도 표기가 달라 안 걸린다.
FENCE_LINE = re.compile(r"^(\s*)([`~]{3,})(.*)$")
INLINE_CODE = re.compile(r"`[^`\n]*`")
LINK_TARGET = re.compile(r"\]\([^)]*\)")
DANGLING_HASH = re.compile(r"#([A-Za-z][\w-]*)")


def strip_code_fences(text: str) -> str:
    """코드펜스(``` 또는 ~~~, 길이 3+) 안의 내용을 빈 줄로 지운다.

    CommonMark는 백틱과 물결표 둘 다 펜스로 인정하므로 둘 다 처리한다(리뷰 지적).
    닫는 펜스는 여는 펜스와 같은 문자·같거나 긴 길이여야 하고 뒤에 정보 문자열이
    없어야 한다. 백틱 여는 줄의 정보 문자열에는 백틱이 올 수 없다(그건 인라인 코드다).
    줄 수는 보존한다(다른 검사의 줄 기준을 흔들지 않기 위함).
    """
    out: list[str] = []
    fence: tuple[str, int] | None = None
    for line in text.splitlines():
        m = FENCE_LINE.match(line)
        if fence is None:
            if m and not (m.group(2)[0] == "`" and "`" in m.group(3)):
                fence = (m.group(2)[0], len(m.group(2)))
                out.append("")  # 여는 펜스 줄 제거
            else:
                out.append(line)
        else:
            fchar, flen = fence
            if (m and m.group(2)[0] == fchar
                    and len(m.group(2)) >= flen and m.group(3).strip() == ""):
                fence = None  # 닫는 펜스
            out.append("")  # 펜스 내부·펜스 줄 모두 제거
    return "\n".join(out)


def load(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def headers_of(path: Path) -> set[str]:
    """문서의 '9-3.' '부록 A.' 같은 헤더 번호 집합.

    주의: PRD·SA 헤더는 반드시 `# N.` / `## N-M.` / `# 부록 A.`처럼
    번호 뒤 마침표 형식이어야 인식된다. 헤더 형식을 바꾸면 그 섹션을
    참조하는 모든 문서가 무더기 FAIL 나므로, 형식 변경 시 이 정규식도
    함께 갱신해야 한다 (rule-source-map.md에도 명시됨).
    """
    nums = set()
    for line in load(path).splitlines():
        m = re.match(r"^#{1,4}\s+(부록 [AB]|\d+(?:-\d+)?)\.", line)
        if m:
            nums.add(m.group(1))
    return nums


def version_of(path: Path, pattern: str, label: str,
               errors: list[str]) -> str | None:
    """정본 헤더에서 버전을 읽는다."""
    if not path.exists():
        errors.append(f"{path.relative_to(ROOT).as_posix()}: 파일이 없다")
        return None
    match = re.search(pattern, load(path))
    if not match:
        errors.append(
            f"{path.relative_to(ROOT).as_posix()}: {label} 버전을 찾을 수 없다"
        )
        return None
    return match.group(1)


def check_lightweight_canonical_refs(errors: list[str]) -> None:
    """경량본의 정본 경로·버전 표기가 실제 정본과 일치하는지 검사한다."""
    versions = {
        "제품 요구사항": (
            "docs/product/DoctorPet-PRD.md",
            version_of(
                PRD, r"\| 문서 버전 \| (v[\d.]+) \|", "PRD", errors
            ),
        ),
        "시스템 설계·ERD·API·상태 머신": (
            "docs/architecture/DoctorPet-SA.md",
            version_of(
                SA, r"\| 문서 버전 \| (v[\d.]+) \|", "SA", errors
            ),
        ),
        "코드 컨벤션": (
            "docs/architecture/DoctorPet-코드컨벤션.md",
            version_of(
                CODE_CONVENTION,
                r"\| 문서 버전 \| (v[\d.]+) \|",
                "코드 컨벤션",
                errors,
            ),
        ),
        "정책 원본": (
            "docs/domain/반려동물병원예약-정책정리본.md",
            version_of(
                POLICY, r"상태:\s*개정판 \((v[\d.]+)\)", "정책", errors
            ),
        ),
    }

    for rel in LIGHTWEIGHT_SUMMARIES:
        text = load(ROOT / rel)
        for label, (path, version) in versions.items():
            if version is None:
                continue
            expected = f"| {label} | `{path}` {version}"
            if expected not in text:
                errors.append(
                    f"{rel}: 정본 참조 불일치 → "
                    f"{label}은 `{path}` {version}이어야 한다"
                )


def expand_range(ref: str) -> list[str] | None:
    """'6-3~6-5' → ['6-3','6-4','6-5'], '9-3' → ['9-3'].

    역순(6-5~6-3)이나 대분류 교차(9-8~10-2)는 잘못된 표기이므로
    None을 반환한다 — 조용히 빈 리스트로 통과시키지 않는다.
    """
    if "~" not in ref:
        return [ref]
    start, end = ref.split("~")
    sm = re.match(r"(\d+)-(\d+)$", start)
    em = re.match(r"(?:(\d+)-)?(\d+)$", end)
    if not (sm and em):
        return None
    major = sm.group(1)
    if em.group(1) and em.group(1) != major:
        return None  # 대분류 교차 범위는 지원하지 않음
    lo, hi = int(sm.group(2)), int(em.group(2))
    if lo > hi:
        return None  # 역순 범위
    return [f"{major}-{i}" for i in range(lo, hi + 1)]


def check_section_refs(rel: str, text: str, prd_headers: set[str],
                       sa_headers: set[str], errors: list[str]) -> None:
    """`PRD §X` / `SA §X` / `SA 부록 A·B` 참조가 실제 헤더에 있는지 검사한다.

    알려진 한계: 코드펜스·인라인 코드 안의 표기도 검사 대상이다.
    현재 문서엔 해당 사례가 없어 방치하며, 예시에 섹션 표기를 쓰게 되면 펜스 스트립을 추가한다.
    """
    for m in SECTION_REF.finditer(text):
        doc_name, ref = m.group(1), m.group(2)
        headers = prd_headers if doc_name == "PRD" else sa_headers
        secs = expand_range(ref)
        if secs is None:
            errors.append(f"{rel}: 잘못된 섹션 범위 표기 → {doc_name} §{ref}")
            continue
        for sec in secs:
            if sec not in headers:
                errors.append(f"{rel}: {doc_name} §{sec} 헤더가 실제 문서에 없다")
    for m in APPENDIX_REF.finditer(text):
        if f"부록 {m.group(1)}" not in sa_headers:
            errors.append(f"{rel}: SA 부록 {m.group(1)} 헤더가 실제 문서에 없다")


def check_dangling_hash_refs(rel: str, text: str, errors: list[str]) -> None:
    """`#A1`·`#Foo`처럼 대응 대상이 없는 참조 태그가 프로즈에 샜는지 검사한다.

    코드펜스(``` 또는 ~~~)·인라인코드·마크다운 링크 앵커(`](path#anchor)`)를 먼저 걷어낸다 —
    셸 주석(`#!/usr/bin/env`)·CSS 색(`#fff`)·파일 앵커 링크는 정상이므로 검사 대상이 아니다.
    남은 프로즈에서 `#`+영문자 토큰만 결함으로 본다. `#숫자`(이슈·PR), `§`(섹션),
    `부록 A/B`, 한글 뒤 `#`은 매치되지 않아 오탐이 없다.
    """
    scrubbed = strip_code_fences(text)
    scrubbed = INLINE_CODE.sub(" ", scrubbed)
    scrubbed = LINK_TARGET.sub(" ", scrubbed)
    for m in DANGLING_HASH.finditer(scrubbed):
        errors.append(
            f"{rel}: 정의 없는 참조 태그 → #{m.group(1)} "
            "(이슈·PR은 #숫자, 섹션은 §N-M·부록 A/B, 파일은 마크다운 링크로 표기한다)"
        )


def main() -> int:
    # Windows 콘솔(cp949)에서도 한글·특수문자 출력이 깨지지 않게 강제
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    errors: list[str] = []

    for name in ("PRD", "SA"):
        if not (PRD if name == "PRD" else SA).exists():
            print(f"FATAL: {name} 문서가 없다")
            return 1
    prd_headers = headers_of(PRD)
    sa_headers = headers_of(SA)

    for rel in HARNESS_DOCS + LIGHTWEIGHT_DOCS + ENHANCEMENT_DOCS:
        doc = ROOT / rel
        if not doc.exists():
            errors.append(f"{rel}: 파일이 없다")
            continue
        text = load(doc)

        # 1. 마크다운 상대 링크
        for m in MD_LINK.finditer(text):
            target = m.group(1)
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            resolved = (doc.parent / target).resolve()
            if not resolved.exists():
                errors.append(f"{rel}: 깨진 링크 → {target}")

        # 2. 백틱 경로 (rule-source-map 표 등)
        for m in BACKTICK_PATH.finditer(text):
            if not (ROOT / m.group(1)).exists():
                errors.append(f"{rel}: 없는 경로 참조 → `{m.group(1)}`")

        # 3. 섹션 참조
        check_section_refs(rel, text, prd_headers, sa_headers, errors)

        # 6. 정의 없는 참조 태그(#A1 등)
        check_dangling_hash_refs(rel, text, errors)

    # PRD·SA 자체의 상호 §참조도 검증한다 (예: PRD가 "SA §9-6"을, SA가 "PRD §7"을 가리키는 경우).
    # 링크·백틱 경로 검사는 제외한다 — 프로즈·표가 많아 오탐이 나므로 섹션 참조만 본다.
    for doc in (PRD, SA):
        rel = doc.relative_to(ROOT).as_posix()
        doc_text = load(doc)
        check_section_refs(rel, doc_text, prd_headers, sa_headers, errors)
        check_dangling_hash_refs(rel, doc_text, errors)

    # 4. PR 템플릿 ↔ github-rules 코드블록 동기 (정본-사본 diff 0)
    tpl = ROOT / ".github/pull_request_template.md"
    rules = ROOT / "docs/collaboration/github-rules.md"
    if not tpl.exists():
        errors.append(".github/pull_request_template.md: 파일이 없다")
    elif not rules.exists():
        errors.append("docs/collaboration/github-rules.md: 파일이 없다")
    else:
        m = re.search(r"### PR 템플릿\n\n```markdown\n(.*?)\n```", load(rules), re.DOTALL)
        if not m:
            errors.append("github-rules.md: '### PR 템플릿' 코드블록을 찾을 수 없다")
        elif m.group(1).strip("\n") != load(tpl).strip("\n"):
            errors.append("PR 템플릿과 github-rules.md 코드블록이 다르다 — 한쪽 수정 시 함께 동기화 필요")

    # 5. 경량본 ↔ 정본 경로·버전 동기
    if not LIGHTWEIGHT_DOCS:
        errors.append("docs/lightweight: 검사할 Markdown 문서가 없다")
    else:
        check_lightweight_canonical_refs(errors)

    if errors:
        print(f"FAIL — {len(errors)}건")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(
        f"PASS — 하네스 문서 {len(HARNESS_DOCS)}개·경량 문서 "
        f"{len(LIGHTWEIGHT_DOCS)}개·고도화 문서 {len(ENHANCEMENT_DOCS)}개 "
        "+ PRD·SA 상호참조, "
        "링크·경로·섹션·정본 버전·참조 태그 이상 없음"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
