package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import java.time.LocalDateTime;

public enum HospitalSlotAvailabilityStatus {
    AVAILABLE,
    RESERVED,
    LEAD_TIME_CLOSED;

    public static HospitalSlotAvailabilityStatus resolve(
            ReservationSlotStatus status,
            LocalDateTime startAt,
            LocalDateTime reservationDeadline
    ) {
        if (status == ReservationSlotStatus.RESERVED) {
            return RESERVED;
        }

        return startAt.isBefore(reservationDeadline)
                ? LEAD_TIME_CLOSED
                : AVAILABLE;
    }
}
