package com.doctorpet.global.gateway.payment.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SensitiveDataMaskerTest {

    @Test
    @DisplayName("시크릿은 내용을 한 글자도 노출하지 않고 길이 힌트만 남긴다")
    void maskSecret() {
        String secret = "billingkey_1234567890abcdef";
        String masked = SensitiveDataMasker.maskSecret(secret);

        assertEquals("****(len=27)", masked);
        assertFalse(masked.contains("bill"), "원본 시크릿 앞부분이 노출되면 안 된다");
        assertFalse(masked.contains("567890abcdef"), "원본 시크릿이 마스킹 결과에 남으면 안 된다");
    }

    @Test
    @DisplayName("null·공백 시크릿은 길이 힌트 없이 전체 마스킹한다")
    void maskBlankSecret() {
        assertEquals("****", SensitiveDataMasker.maskSecret(null));
        assertEquals("****", SensitiveDataMasker.maskSecret(""));
        assertEquals("****", SensitiveDataMasker.maskSecret("   "));
    }

    @Test
    @DisplayName("짧은 시크릿도 내용은 노출하지 않는다")
    void maskShortSecret() {
        assertEquals("****(len=2)", SensitiveDataMasker.maskSecret("ab"));
        assertFalse(SensitiveDataMasker.maskSecret("ab").contains("a"), "짧아도 내용은 노출되면 안 된다");
    }

    @Test
    @DisplayName("카드번호는 뒤 4자리만 남기고 마스킹한다")
    void maskCardNumber() {
        assertEquals("****-****-****-1111", SensitiveDataMasker.maskCardNumber("4242-4242-4242-1111"));
        assertEquals("****-****-****-1111", SensitiveDataMasker.maskCardNumber("4242 4242 4242 1111"));
    }

    @Test
    @DisplayName("4자리 미만·null 카드번호는 전체 마스킹한다")
    void maskShortCardNumber() {
        assertEquals("****", SensitiveDataMasker.maskCardNumber("12"));
        assertEquals("****", SensitiveDataMasker.maskCardNumber(null));
    }
}
