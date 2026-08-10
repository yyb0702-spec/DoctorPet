package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;

public record HospitalReservationListItemResponse(
        Long reservationId,
        Long memberId,
        Long petId,
        String petName,
        // 노쇼 직전 확인 전화 등 병원-보호자 연락 수단이 없던 문제 대응(기능 구멍 점검). 기존
        // 회원은 phone이 null일 수 있어 이 필드도 null일 수 있다.
        String guardianPhone,
        LocalDateTime reservedAt,
        ReservationStatus reservationStatus,
        String rejectionReason,
        ReservationHistoryResponse reservationHistory
) {

    public static HospitalReservationListItemResponse from(
            Reservation reservation,
            String guardianPhone,
            LocalDateTime reservedAt,
            ReservationHistoryResponse history
    ) {
        return new HospitalReservationListItemResponse(
                reservation.getId(),
                reservation.getMemberId(),
                reservation.getPetId(),
                reservation.getPetNameSnapshot(),
                guardianPhone,
                reservedAt,
                reservation.getStatus(),
                reservation.getRejectReason(),
                history
        );
    }
}
