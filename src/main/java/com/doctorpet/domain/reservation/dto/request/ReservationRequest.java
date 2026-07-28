package com.doctorpet.domain.reservation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReservationRequest(
        @NotNull(message = "반려동물 ID는 필수입니다.")
        @Positive(message = "반려동물 ID는 양수여야 합니다.")
        Long petId,

        @NotNull(message = "예약 슬롯 ID는 필수입니다.")
        @Positive(message = "예약 슬롯 ID는 양수여야 합니다.")
        Long slotId,

        @NotNull(message = "결제수단 ID는 필수입니다.")
        @Positive(message = "결제수단 ID는 양수여야 합니다.")
        Long paymentMethodId,

        @NotBlank(message = "반려동물 이름 스냅샷은 필수입니다.")
        @Size(max = 50, message = "반려동물 이름은 50자 이하여야 합니다.")
        String petNameSnapshot,

        @NotBlank(message = "반려동물 종 스냅샷은 필수입니다.")
        @Size(max = 10, message = "반려동물 종은 10자 이하여야 합니다.")
        String petSpeciesSnapshot
) {
}
