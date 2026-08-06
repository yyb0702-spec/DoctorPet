package com.doctorpet.global.gateway.payment.dto;

import java.time.LocalDateTime;

/**
 * 결제 취소·환불 결과(이슈 #37). 취소가 성립한 경우에만 반환되고, 실패는
 * {@link com.doctorpet.global.gateway.payment.PaymentGatewayException}으로 던진다.
 *
 * <p>이미 취소된 결제에 같은 멱등키로 재요청한 경우도 성공으로 본다 — 공급자가 기존 취소 결과를 돌려주므로
 * 상위 입장에서는 "취소가 성립했다"는 사실이 같다(#37 재시도 안전성의 근거).
 *
 * @param pgCancelId  공급자 취소 식별자(감사·대조용). 공급자가 주지 않으면 null일 수 있다
 * @param amount      실제로 취소된 금액(원). 상위가 요청 금액과 대조한다
 * @param cancelledAt 공급자 기준 취소 시각. 응답에 없으면 null이며 상위가 서버 시각으로 대체한다
 */
public record PaymentCancelResult(
        String pgCancelId,
        int amount,
        LocalDateTime cancelledAt
) {
}
