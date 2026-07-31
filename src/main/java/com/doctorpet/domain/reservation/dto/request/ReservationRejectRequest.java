package com.doctorpet.domain.reservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReservationRejectRequest(
        @NotBlank(message = "예약 거절 사유는 필수입니다.")
        @Size(max = 255, message = "예약 거절 사유는 255자 이하여야 합니다.")
        String rejectReason
) {
}
