package com.doctorpet.global.gateway.payment.dto;

import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;

import java.time.LocalDateTime;

/**
 * 빌링키 결제 승인 결과(도메인 중립).
 *
 * @param status         공급자 중립 결제 상태
 * @param pgPaymentId    공급자 결제 식별자(단건 조회용). {@code payments.pg_payment_id}에 저장한다.
 * @param approvedAmount 공급자가 확인한 승인 금액(서버가 요청 금액과 대조 검증)
 * @param approvedAt     승인 시각(공급자 응답 기준, 없으면 null)
 */
public record PaymentApproveResult(
        GatewayPaymentStatus status,
        String pgPaymentId,
        int approvedAmount,
        LocalDateTime approvedAt
) {
}
