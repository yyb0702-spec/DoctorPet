package com.doctorpet.global.gateway.payment.support;

/**
 * 결제 로그·오류 메시지에 민감정보(API 키·빌링키·카드번호)가 노출되지 않도록 마스킹한다(보안 규칙, 이슈 #38).
 * 원본은 저장·출력하지 않는다 — 표시는 마스킹 값만 쓴다.
 */
public final class SensitiveDataMasker {

    private static final String EMPTY_MASK = "****";

    private SensitiveDataMasker() {
    }

    /**
     * 시크릿(빌링키·API 키 등)을 마스킹한다. 내용은 한 글자도 노출하지 않고 길이 힌트만 남겨
     * 값의 존재·누락·잘림만 로그로 구분 가능하게 한다. null·공백은 전체 마스킹한다.
     */
    public static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_MASK;
        }
        return EMPTY_MASK + "(len=" + value.length() + ")";
    }

    /**
     * 카드번호를 뒤 4자리만 남기고 마스킹한다. 뒤 4자리 미만이거나 null이면 전체를 마스킹한다.
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return EMPTY_MASK;
        }
        String digitsOnly = cardNumber.replaceAll("\\s|-", "");
        if (digitsOnly.length() < 4) {
            return EMPTY_MASK;
        }
        String last4 = digitsOnly.substring(digitsOnly.length() - 4);
        return "****-****-****-" + last4;
    }
}
