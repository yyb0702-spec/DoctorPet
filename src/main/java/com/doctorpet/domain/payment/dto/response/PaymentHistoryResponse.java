package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;

/*
  결제 내역 조회 응답(#47, SA §8-7). 온·오프라인 결제 상태를 status+channel로 일관되게 반환한다.
  민감정보는 노출하지 않는다 — 빌링키·카드번호 원본은 저장 자체가 없고, 표시용 카드 스냅샷(brand·last4)만 담는다.
  PG/멱등 식별자(pg_payment_id·merchant_payment_id)는 내부 값이라 응답에 포함하지 않는다.
  failedAt은 자동 청구 실패로 오프라인 전환된 시각(offline_required_at)이다.
 */
public record PaymentHistoryResponse(
        Long paymentId,
        Long reservationId,
        PaymentStatus status,
        PaymentChannel paymentChannel,
        int amount,
        String cardBrandSnapshot,
        String cardLast4Snapshot,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        LocalDateTime failedAt,
        LocalDateTime offlineSettledAt
) {

    public static PaymentHistoryResponse from(Payment payment) {
        return new PaymentHistoryResponse(
                payment.getId(),
                payment.getReservationId(),
                payment.getStatus(),
                payment.getPaymentChannel(),
                payment.getAmount(),
                payment.getCardBrandSnapshot(),
                payment.getCardLast4Snapshot(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getPaidAt(),
                payment.getOfflineRequiredAt(),
                payment.getOfflineSettledAt());
    }
}
