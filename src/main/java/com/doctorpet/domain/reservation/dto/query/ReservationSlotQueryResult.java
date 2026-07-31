package com.doctorpet.domain.reservation.dto.query;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import java.time.LocalDateTime;

public record ReservationSlotQueryResult(
        Long slotId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        ReservationSlotStatus status
) {

    public static ReservationSlotQueryResult from(ReservationSlot slot) {
        return new ReservationSlotQueryResult(
                slot.getId(),
                slot.getStartAt(),
                slot.getEndAt(),
                slot.getStatus()
        );
    }
}
