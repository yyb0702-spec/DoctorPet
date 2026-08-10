package com.doctorpet.global.gateway.payment.dto;

/**
 * 결제 취소·환불 요청(이슈 #37). 전액 환불만 지원하므로 {@code amount}는 승인 금액과 같다
 * (부분 취소는 확장 — PRD·SA 모두 별도 확장으로 분류).
 *
 * @param merchantPaymentId 취소 대상 결제의 멱등키(PortOne 결제 식별자로 그대로 쓰인다)
 * @param merchantRefundId  이 취소 요청의 멱등키. 재시도에도 <b>같은 값</b>을 넘겨야 공급자가 이중 취소를
 *                          흡수한다 — 값이 달라지면 PG도 중복 취소를 막을 수 없다(#37)
 * @param amount            취소 금액(원)
 * @param reason            취소 사유(공급자 표기용, 민감정보 금지)
 */
public record PaymentCancelCommand(
        String merchantPaymentId,
        String merchantRefundId,
        int amount,
        String reason
) {
}
