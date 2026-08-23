// 병원 스태프가 보는 회원 진료·결제 이력 한 행 — 자병원에서의 과거 예약 + 활성 결제(SA §8-6).
package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;

/**
 * 병원 회원 이력 목록의 한 행. 자병원에서의 그 회원 예약(과거·현재)과 붙은 활성 결제를 담는다.
 * 결제 없는 예약은 결제 필드가 null이다. 영수증 노출 여부는 화면이 paymentStatus로 판단한다
 * (PAID·OFFLINE_PAID·REFUNDED만 영수증 대상).
 */
public record HospitalMemberHistoryItemResponse(
        Long reservationId,
        LocalDateTime reservedAt,
        String petName,
        String petSpecies,
        ReservationStatus reservationStatus,
        Long paymentId,
        PaymentStatus paymentStatus,
        Integer amount
) {
}
