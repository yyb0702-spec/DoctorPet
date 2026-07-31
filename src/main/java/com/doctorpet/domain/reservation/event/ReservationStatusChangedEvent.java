package com.doctorpet.domain.reservation.event;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;

public record ReservationStatusChangedEvent(
        Long reservationId,
        Long memberId,
        ReservationStatus status,
        LocalDateTime occurredAt
) {
}
