package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;

/**
 * 환불 확정(Tx2) 결과를 오케스트레이터로 넘기는 값 객체(#37).
 *
 * @param response 확정 후 결제 상태
 * @param applied  이 호출이 실제로 PAID→REFUNDED 전이를 수행했는지. false면 다른 경로가 먼저 확정한 것이므로
 *                 알림을 중복 발행하지 않는다(청구 후확정의 applied와 같은 역할)
 */
public record RefundOutcome(
        PaymentHistoryResponse response,
        boolean applied
) {
}
