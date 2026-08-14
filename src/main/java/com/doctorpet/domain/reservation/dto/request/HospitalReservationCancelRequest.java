package com.doctorpet.domain.reservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record HospitalReservationCancelRequest(
        @NotBlank(message = "병원 예약 취소 사유는 필수입니다.")
        @Size(max = 255, message = "병원 예약 취소 사유는 255자 이하여야 합니다.")
        String reason
) {
}
