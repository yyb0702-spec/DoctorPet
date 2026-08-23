// 병원 스태프 결제 목록 한 행 — 자병원 예약 + 활성 결제 스냅샷(SA §8-7).
package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;

/**
 * 병원 결제/미수금 대시보드 목록의 한 행. 자병원 예약에 붙은 활성 결제(superseded_at IS NULL)를
 * 반환하므로 결제 관련 필드는 항상 채워진다. petName·reservedAt은 예약 스냅샷·슬롯 시작 시각이다.
 * failedAt은 자동결제 실패로 현장 수납이 요구된 시각(offline_required_at)이다.
 */
public record HospitalPaymentListItemResponse(
        Long reservationId,
        Long memberId,
        Long petId,
        String petName,
        LocalDateTime reservedAt,
        Long paymentId,
        PaymentStatus paymentStatus,
        Integer amount,
        LocalDateTime paidAt,
        LocalDateTime offlineSettledAt,
        LocalDateTime refundedAt,
        LocalDateTime failedAt
) {
}
