package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;

import java.time.LocalDateTime;

public record ReservationWaitlistResponse(
        Long waitlistId,
        Long slotId,
        ReservationWaitlistStatus status,
        LocalDateTime requestedAt,
        LocalDateTime offeredAt,
        LocalDateTime offerExpiresAt,
        LocalDateTime respondedAt,
        LocalDateTime canceledAt
) {

    public static ReservationWaitlistResponse from(ReservationWaitlist waitlist) {
        return new ReservationWaitlistResponse(
                waitlist.getId(),
                waitlist.getSlotId(),
                waitlist.getStatus(),
                waitlist.getCreatedAt(),
                waitlist.getOfferedAt(),
                waitlist.getOfferExpiresAt(),
                waitlist.getRespondedAt(),
                waitlist.getCanceledAt()
        );
    }
}
