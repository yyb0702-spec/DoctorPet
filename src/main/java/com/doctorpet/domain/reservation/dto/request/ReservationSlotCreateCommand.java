package com.doctorpet.domain.reservation.dto.request;

import java.time.LocalDateTime;

public record ReservationSlotCreateCommand(
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}
