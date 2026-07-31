package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;

public record HospitalReservationListItemResponse(
        Long reservationId,
        Long memberId,
        Long petId,
        String petName,
        LocalDateTime reservedAt,
        ReservationStatus reservationStatus,
        String rejectionReason,
        ReservationHistoryResponse reservationHistory
) {

    public static HospitalReservationListItemResponse from(
            Reservation reservation,
            LocalDateTime reservedAt,
            ReservationHistoryResponse history
    ) {
        return new HospitalReservationListItemResponse(
                reservation.getId(),
                reservation.getMemberId(),
                reservation.getPetId(),
                reservation.getPetNameSnapshot(),
                reservedAt,
                reservation.getStatus(),
                reservation.getRejectReason(),
                history
        );
    }
}
