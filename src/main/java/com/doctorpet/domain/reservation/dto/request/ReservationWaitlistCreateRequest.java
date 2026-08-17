package com.doctorpet.domain.reservation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationWaitlistCreateRequest(
        @NotNull
        @Positive
        Long slotId
) {
}
