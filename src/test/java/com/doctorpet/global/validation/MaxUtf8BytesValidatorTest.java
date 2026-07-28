package com.doctorpet.global.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — 순수 단위 테스트. Bean Validation 컨텍스트 없이 검증 로직 자체만 확인한다.
 * 리뷰 지적: @Size(max = 72)는 문자 수 기준이라 한글·이모지처럼 문자당 여러 바이트를 쓰는
 * 입력에서 BCrypt의 실제 72바이트 제한과 어긋난다 — 그 어긋남이 실제로 발생하는지 확인한다.
 */
class MaxUtf8BytesValidatorTest {

    @Test
    @DisplayName("UTF-8 바이트 수가 제한 이하면 통과한다")
    void isValid_withinLimit() {
        MaxUtf8BytesValidator validator = newValidator(72);

        // 한글 24자 = 72바이트(한글 1자 = UTF-8 3바이트) — 경계값, 통과해야 한다.
        assertThat(validator.isValid("가".repeat(24), null)).isTrue();
    }

    @Test
    @DisplayName("문자 수는 제한 이하지만 UTF-8 바이트 수는 제한을 초과하면 실패한다(한글 등 멀티바이트 입력)")
    void isValid_charCountOkButByteCountExceeds() {
        MaxUtf8BytesValidator validator = newValidator(72);

        // 한글 25자 = 75바이트 — 문자 수(25)는 72 미만이지만 바이트 수는 초과한다.
        // @Size(max = 72)였다면 통과했을 입력이 실제로는 BCrypt 한도를 넘는 상황을 재현한다.
        String korean25Chars = "가".repeat(25);
        assertThat(korean25Chars.length()).isLessThan(72);
        assertThat(validator.isValid(korean25Chars, null)).isFalse();
    }

    @Test
    @DisplayName("null은 통과시킨다(필수 여부는 @NotBlank 등 다른 제약이 담당)")
    void isValid_nullIsValid() {
        MaxUtf8BytesValidator validator = newValidator(72);

        assertThat(validator.isValid(null, null)).isTrue();
    }

    private MaxUtf8BytesValidator newValidator(int maxBytes) {
        MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();
        validator.initialize(new MaxUtf8Bytes() {
            @Override
            public int value() {
                return maxBytes;
            }

            @Override
            public String message() {
                return "";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return new Class[0];
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return MaxUtf8Bytes.class;
            }
        });
        return validator;
    }
}
