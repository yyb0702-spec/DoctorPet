#!/usr/bin/env python3
"""harness_check.py 회귀 테스트 — 정의 없는 참조 태그 검출의 양성·음성 경계 고정.

CI가 clean 문서에만 harness_check를 돌리면, 정규식이 망가져 #A1을 더 이상 못 잡아도
실제 문서에 그 태그가 없는 한 계속 통과한다(리뷰 지적). 그 경계를 여기서 못박는다.

실행: python -m unittest scripts.test_harness_check
      또는 python -m unittest discover -s scripts -p "test_*.py"
"""

from __future__ import annotations

import unittest

import harness_check as hc


def _errors(text: str) -> list[str]:
    errs: list[str] = []
    hc.check_dangling_hash_refs("test.md", text, errs)
    return errs


class DanglingHashPositive(unittest.TestCase):
    """정의 없는 #영문자 태그는 반드시 잡아야 한다."""

    def test_detects_single_tag(self):
        errs = _errors("이 문장에는 정의 없는 태그(#A1)가 있다.")
        self.assertEqual(len(errs), 1)
        self.assertIn("#A1", errs[0])

    def test_detects_multiple_tags(self):
        errs = _errors("정의 없는 태그(#A1·#Foo) 두 개.")
        joined = " ".join(errs)
        self.assertIn("#A1", joined)
        self.assertIn("#Foo", joined)


class DanglingHashNegative(unittest.TestCase):
    """정상 표기는 절대 잡으면 안 된다(오탐 0)."""

    def test_issue_and_pr_numbers(self):
        self.assertEqual(_errors("이슈 #123 과 PR #87 참고."), [])

    def test_markdown_link_anchor(self):
        self.assertEqual(
            _errors("[hot path](../ai/context-router.md#hot-path) 참고."), [])

    def test_inline_code(self):
        self.assertEqual(_errors("인라인 `#include` 와 `#fff` 는 코드다."), [])

    def test_korean_after_hash(self):
        self.assertEqual(_errors("해시태그 #환불 은 한글이라 안 걸린다."), [])

    def test_backtick_fence(self):
        text = (
            "설명 문장.\n\n"
            "```bash\n"
            "#!/usr/bin/env bash\n"
            "echo '#A9 in fence'\n"
            "```\n\n"
            "끝 문장.\n"
        )
        self.assertEqual(_errors(text), [])

    def test_tilde_fence(self):
        # CommonMark ~~~ 펜스 안의 CSS/셸 해시 문자열 (리뷰 지적 핵심 케이스)
        text = (
            "설명 문장.\n\n"
            "~~~css\n"
            ".a { color: #fff; }\n"
            "~~~\n\n"
            "~~~\n"
            "#include <stdio.h>\n"
            "~~~\n\n"
            "끝 문장.\n"
        )
        self.assertEqual(_errors(text), [])

    def test_longer_and_indented_fence(self):
        # 길이 4 펜스 + 들여쓴 펜스도 인식해야 한다
        text = (
            "설명.\n\n"
            "````text\n"
            "#Bad1 안에 있음\n"
            "````\n\n"
            "리스트 안:\n\n"
            "    setup\n"
            "- 항목\n\n"
            "~~~~\n"
            "#Bad2 안에 있음\n"
            "~~~~\n"
        )
        self.assertEqual(_errors(text), [])


class DanglingHashFenceBoundary(unittest.TestCase):
    """펜스 밖의 태그는 여전히 잡아야 한다(펜스 제거가 과하지 않아야)."""

    def test_tag_after_fence_still_caught(self):
        text = (
            "```\n"
            "#InFence\n"
            "```\n"
            "펜스 밖 태그(#Outside) 는 잡혀야 한다.\n"
        )
        errs = _errors(text)
        joined = " ".join(errs)
        self.assertIn("#Outside", joined)
        self.assertNotIn("#InFence", joined)


if __name__ == "__main__":
    unittest.main()
