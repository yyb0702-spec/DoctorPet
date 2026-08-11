#!/usr/bin/env python3
"""promote_docs.py 회귀 테스트 — 임시 문서 트리로 승격 동작을 고정한다.

CI가 promote_docs.py를 한 번도 실행하지 않아, SA·PRD 동시 승격·마이너 자릿수 경계·
특수문자 엔트리·헤더 누락·--dry-run 파일 불변 같은 핵심 동작이 깨져도 못 잡던
사각지대를 막는다(리뷰 지적). 실제 정본을 건드리지 않도록 tmp 디렉터리를 대상으로 한다.

실행: python -m unittest discover -s scripts -p "test_*.py"
"""

from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

import promote_docs as pd

SA_DOC = (
    "| 문서 버전 | v1.51 |\n\n"
    "> 변경 이력 — v1.50: 첫째. v1.51: 둘째.\n\n"
    "본문.\n"
)
PRD_DOC = (
    "| 문서 버전 | v3.24 |\n\n"
    "> 변경 이력 — v3.23: 가. v3.24: 나.\n\n"
    "본문.\n"
)
LW_DOC = (
    "| 제품 요구사항 | `docs/product/DoctorPet-PRD.md` v3.24 |\n"
    "| 시스템 설계·ERD·API·상태 머신 | `docs/architecture/DoctorPet-SA.md` v1.51, REST API는 §8 |\n"
)


class PromoteDocsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "docs/architecture").mkdir(parents=True)
        (self.tmp / "docs/product").mkdir(parents=True)
        (self.tmp / "docs/lightweight").mkdir(parents=True)
        self.sa = self.tmp / "docs/architecture/DoctorPet-SA.md"
        self.prd = self.tmp / "docs/product/DoctorPet-PRD.md"
        self.lw = self.tmp / "docs/lightweight/DB-경량본.md"
        self.readme = self.tmp / "docs/lightweight/README.md"
        self.sa.write_text(SA_DOC, encoding="utf-8")
        self.prd.write_text(PRD_DOC, encoding="utf-8")
        self.lw.write_text(LW_DOC, encoding="utf-8")
        self.readme.write_text("경량본은 건드리지 않는다.\n", encoding="utf-8")

        # 모듈 전역을 임시 트리로 교체(ROOT은 요약 출력의 relative_to 기준)
        self._orig = (pd.ROOT, pd.SA, pd.PRD, pd.LIGHTWEIGHT_DIR, pd._run_harness)
        pd.ROOT = self.tmp
        pd.SA = self.sa
        pd.PRD = self.prd
        pd.LIGHTWEIGHT_DIR = self.tmp / "docs/lightweight"
        pd._run_harness = lambda: 0  # 실제 harness_check는 여기서 검증 대상 아님

    def tearDown(self):
        (pd.ROOT, pd.SA, pd.PRD, pd.LIGHTWEIGHT_DIR, pd._run_harness) = self._orig

    def _run(self, *argv: str) -> int:
        old = sys.argv
        sys.argv = ["promote_docs.py", *argv]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                return pd.main()
        finally:
            sys.argv = old

    # --- 단위: _next_version 경계 ---
    def test_next_version_minor_increment(self):
        self.assertEqual(pd._next_version("| 문서 버전 | v1.51 |", "SA"),
                         ("v1.51", "v1.52"))

    def test_next_version_double_digit_boundary(self):
        # v1.9 → v1.10 (문자열 정렬이 아니라 정수 +1)
        self.assertEqual(pd._next_version("| 문서 버전 | v1.9 |", "SA"),
                         ("v1.9", "v1.10"))

    def test_next_version_missing_header_raises(self):
        with self.assertRaises(pd.PromoteError):
            pd._next_version("헤더가 없는 문서", "SA")

    # --- SA 단독 승격 ---
    def test_sa_bump(self):
        rc = self._run("--sa", "테스트 항목")
        self.assertEqual(rc, 0)
        sa = self.sa.read_text(encoding="utf-8")
        self.assertIn("| 문서 버전 | v1.52 |", sa)
        self.assertIn("v1.52: 테스트 항목.", sa)  # 마침표 자동 부착
        lw = self.lw.read_text(encoding="utf-8")
        # 경량본 SA 참조 동기 + 트레일링 텍스트 보존
        self.assertIn("`docs/architecture/DoctorPet-SA.md` v1.52, REST API는 §8", lw)
        # PRD 참조는 그대로
        self.assertIn("`docs/product/DoctorPet-PRD.md` v3.24", lw)

    # --- SA·PRD 동시 승격 (한쪽이 다른 쪽을 덮지 않아야) ---
    def test_sa_and_prd_together(self):
        rc = self._run("--sa", "에스에이.", "--prd", "피알디.")
        self.assertEqual(rc, 0)
        self.assertIn("| 문서 버전 | v1.52 |", self.sa.read_text(encoding="utf-8"))
        self.assertIn("| 문서 버전 | v3.25 |", self.prd.read_text(encoding="utf-8"))
        lw = self.lw.read_text(encoding="utf-8")
        self.assertIn("`docs/architecture/DoctorPet-SA.md` v1.52, REST API는 §8", lw)
        self.assertIn("`docs/product/DoctorPet-PRD.md` v3.25", lw)

    # --- 특수문자·백슬래시 엔트리도 안전 (re.sub 치환 파손 없음) ---
    def test_special_char_entry(self):
        entry = r"백슬래시 \g<1> 와 $1 와 `코드` 포함."
        rc = self._run("--sa", entry)
        self.assertEqual(rc, 0)
        sa = self.sa.read_text(encoding="utf-8")
        self.assertIn(f"v1.52: {entry}", sa)  # 원문 그대로 보존

    # --- 마침표 중복 부착 안 함 ---
    def test_period_not_doubled(self):
        self._run("--sa", "이미 마침표로 끝난다.")
        sa = self.sa.read_text(encoding="utf-8")
        self.assertIn("v1.52: 이미 마침표로 끝난다.", sa)
        self.assertNotIn("끝난다..", sa)

    # --- --dry-run 은 파일을 바꾸지 않는다 ---
    def test_dry_run_is_immutable(self):
        before = (self.sa.read_text(encoding="utf-8"),
                  self.prd.read_text(encoding="utf-8"),
                  self.lw.read_text(encoding="utf-8"))
        rc = self._run("--dry-run", "--sa", "미리보기", "--prd", "미리보기")
        self.assertEqual(rc, 0)
        after = (self.sa.read_text(encoding="utf-8"),
                 self.prd.read_text(encoding="utf-8"),
                 self.lw.read_text(encoding="utf-8"))
        self.assertEqual(before, after)

    # --- 인자 없으면 에러 종료 ---
    def test_no_target_errors(self):
        with self.assertRaises(SystemExit) as cm:
            self._run()  # --sa/--prd 없음
        self.assertNotEqual(cm.exception.code, 0)

    # --- 헤더 없는 정본이면 실패(rc=1), 조용히 통과 안 함 ---
    def test_missing_header_returns_error(self):
        self.sa.write_text("헤더도 변경 이력도 없다.\n", encoding="utf-8")
        rc = self._run("--sa", "항목")
        self.assertEqual(rc, 1)
        # 실패 시 파일을 쓰지 않았는지(경량본 불변)
        self.assertIn("v1.51, REST API는 §8", self.lw.read_text(encoding="utf-8"))

    # --- 헤더는 있지만 '> 변경 이력' 줄이 없으면 실패(rc=1), 파일 불변 ---
    def test_missing_changelog_returns_error(self):
        self.sa.write_text("| 문서 버전 | v1.51 |\n\n본문만 있고 변경 이력 줄이 없다.\n",
                           encoding="utf-8")
        rc = self._run("--sa", "항목")
        self.assertEqual(rc, 1)
        # 헤더 치환은 메모리에서만 일어났고 실제 파일은 안 써야 한다
        self.assertIn("| 문서 버전 | v1.51 |", self.sa.read_text(encoding="utf-8"))
        self.assertIn("v1.51, REST API는 §8", self.lw.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
