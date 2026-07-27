package com.doctorpet.global.gateway.payment.dto;

/**
 * 결제 취소·환불 요청(확장 지점). 환불은 MVP 제외이며(SA §4 payments·§9-4),
 * 이 타입은 {@code PaymentGateway#cancel}의 확장 지점을 계약에 명시하기 위해서만 존재한다.
 *
 * @param merchantPaymentId 취소 대상 결제의 멱등키
 * @param amount            취소 금액(부분 취소 확장 대비, 전액이면 승인 금액과 동일)
 * @param reason            취소 사유
 */
public record PaymentCancelCommand(
        String merchantPaymentId,
        int amount,
        String reason
) {
}
