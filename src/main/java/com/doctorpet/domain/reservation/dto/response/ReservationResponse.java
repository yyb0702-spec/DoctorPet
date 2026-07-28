package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;

import java.time.LocalDateTime;


public record ReservationResponse (

        Long reservationId,
        Long petId,
        Long hospitalId,
        Long slotId,
        ReservationStatus status,
        LocalDateTime requestedAt
){
    public static ReservationResponse from(
            com.doctorpet.domain.reservation.entity.Reservation reservation
    ) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getPetId(),
                reservation.getHospitalId(),
                reservation.getSlotId(),
                reservation.getStatus(),
                reservation.getRequestedAt()
        );
    }
}
