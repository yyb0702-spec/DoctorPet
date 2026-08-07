package com.doctorpet.domain.reservation.dto.response;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

public record ReservationCheckInResponse(
        Long reservationId,
        ReservationStatus status,
        OffsetDateTime checkedInAt
) {

    public static ReservationCheckInResponse from(
            Long reservationId,
            LocalDateTime checkedInAt
    ) {
        return new ReservationCheckInResponse(
                reservationId,
                ReservationStatus.CHECKED_IN,
                checkedInAt.atZone(SEOUL_ZONE_ID).toOffsetDateTime()
        );
    }
}
