package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;

/*
  진료비 청구 결과(#34). 승인 성공(PAID)·오프라인 필요(OFFLINE_REQUIRED)·미확정(PENDING)을 status로 표현한다.
  게이트웨이 승인 실패는 HTTP 에러가 아니라 OFFLINE_REQUIRED status로 내려간다(SA §9-4) — 스태프가 그 자리에서
  다른 수단으로 오프라인 수납하면 된다. 빌링키 원본·카드번호는 담지 않는다(brand·last4 스냅샷만).
 */
public record PaymentChargeResponse(
        Long paymentId,
        Long reservationId,
        PaymentStatus status,
        int amount,
        String cardBrandSnapshot,
        String cardLast4Snapshot,
        String failureReason
) {

    public static PaymentChargeResponse from(Payment payment) {
        return new PaymentChargeResponse(
                payment.getId(),
                payment.getReservationId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCardBrandSnapshot(),
                payment.getCardLast4Snapshot(),
                payment.getFailureReason()
        );
    }
}
