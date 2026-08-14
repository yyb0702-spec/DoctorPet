package com.doctorpet.domain.reservation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 보호자가 진료 전 예약에 연결된 결제수단을 재지정할 때 쓰는 요청이다. */
public record ReservationPaymentMethodUpdateRequest(
        @NotNull @Positive Long paymentMethodId
) {
}
