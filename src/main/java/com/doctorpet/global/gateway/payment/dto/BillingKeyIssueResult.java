package com.doctorpet.global.gateway.payment.dto;

/**
 * 빌링키 발급 결과 검증 응답(도메인 중립).
 * 게이트웨이는 빌링키 원본을 반환하지 않고 저장·표시에 안전한 값(brand·last4)만 돌려준다(보안 규칙).
 *
 * @param valid     발급된 빌링키가 유효(사용 가능)한지 여부
 * @param cardBrand 카드 브랜드(스냅샷용, 없으면 null)
 * @param cardLast4 카드 뒷 4자리(스냅샷용, 없으면 null)
 * @param issueId   발급 요청 시 SDK로 넘긴 발급 건별 고유 ID(PortOne 빌링키 조회의 issueId).
 *                  콜백 빌링키를 특정 발급 시도에 귀속할 때만 쓴다. 상수인 merchantId(고객사 ID)와 다르다.
 */
public record BillingKeyIssueResult(
        boolean valid,
        String cardBrand,
        String cardLast4,
        String issueId
) {

    public BillingKeyIssueResult(boolean valid, String cardBrand, String cardLast4) {
        this(valid, cardBrand, cardLast4, null);
    }
}
