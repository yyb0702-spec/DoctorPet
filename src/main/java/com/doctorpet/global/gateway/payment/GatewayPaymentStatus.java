package com.doctorpet.global.gateway.payment;

/**
 * 외부 결제 공급자에 중립적인 결제 결과 상태.
 * 도메인의 {@code PaymentStatus}(PENDING/PAID/OFFLINE_REQUIRED/OFFLINE_PAID)로의 전이는
 * 게이트웨이가 아니라 상위 결제 서비스가 결정한다(SA §5-2).
 */
public enum GatewayPaymentStatus {

    /** 승인 완료(빌링키 결제 성공). */
    PAID,

    /** 결과 미확정 — 응답 유실·타임아웃 등으로 승인 여부를 단정할 수 없음. 단건 조회로 재확정한다(SA §9-4). */
    PENDING,

    /** 승인 실패(공급자가 명확히 거절). 실패 사유는 {@link GatewayFailureReason} 참고. */
    FAILED
}
