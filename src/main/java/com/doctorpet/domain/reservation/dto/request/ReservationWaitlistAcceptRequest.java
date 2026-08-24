package com.doctorpet.domain.reservation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 승급 제안 수락 시 새 REQUESTED 예약에 필요한 보호자 선택값. */
public record ReservationWaitlistAcceptRequest(
        @NotNull @Positive Long petId,
        @NotNull @Positive Long paymentMethodId
) {
}
