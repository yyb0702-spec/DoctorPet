package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.reservation.dto.query.ReservationSlotQueryResult;
import java.time.LocalDateTime;

public record HospitalSlotResponse(
        Long slotId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        HospitalSlotAvailabilityStatus availabilityStatus
) {

    public static HospitalSlotResponse from(
            ReservationSlotQueryResult slot,
            LocalDateTime reservationDeadline
    ) {
        return new HospitalSlotResponse(
                slot.slotId(),
                slot.startAt(),
                slot.endAt(),
                HospitalSlotAvailabilityStatus.resolve(
                        slot.status(),
                        slot.startAt(),
                        reservationDeadline
                )
        );
    }
}
