package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;

import java.time.LocalDateTime;

public record ReservationListItemResponse(
        Long reservationId,
        Long hospitalId,
        String hospitalName,
        Long petId,
        String petName,
        LocalDateTime reservedAt,
        ReservationStatus reservationStatus,
        String paymentStatus,
        ReservationProgressStatus progressStatus
) {

    public static ReservationListItemResponse from(
            Reservation reservation,
            String hospitalName,
            LocalDateTime reservedAt,
            String paymentStatus
    ) {
        return new ReservationListItemResponse(
                reservation.getId(),
                reservation.getHospitalId(),
                hospitalName,
                reservation.getPetId(),
                reservation.getPetNameSnapshot(),
                reservedAt,
                reservation.getStatus(),
                paymentStatus,
                ReservationProgressStatus.from(
                        reservation.getStatus(),
                        paymentStatus
                )
        );
    }
}
