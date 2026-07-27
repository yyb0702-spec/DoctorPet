package com.doctorpet.global.gateway.payment.dto;

import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;

/**
 * 결제 단건 조회 결과(도메인 중립). 타임아웃·응답 유실 시 승인 여부를 재확정하는 데 쓴다(SA §9-4).
 *
 * @param status      공급자 중립 결제 상태
 * @param pgPaymentId 공급자 결제 식별자(없으면 null)
 * @param paidAmount  공급자가 확인한 결제 금액(승인되지 않았으면 0)
 */
public record PaymentQueryResult(
        GatewayPaymentStatus status,
        String pgPaymentId,
        int paidAmount
) {
}
