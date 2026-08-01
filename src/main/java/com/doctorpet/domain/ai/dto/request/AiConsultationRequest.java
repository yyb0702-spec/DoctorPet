package com.doctorpet.domain.ai.dto.request;

import com.doctorpet.domain.pet.entity.PetSpecies;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AiConsultationRequest(
        @NotBlank(message = "증상 내용은 필수입니다.")
        @Size(max = 1000, message = "증상 내용은 1,000자를 초과할 수 없습니다.")
        String symptomText,

        @NotNull(message = "축종은 필수입니다.")
        PetSpecies species,

        String region,

        @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
        BigDecimal longitude
) {

    @AssertTrue(message = "위도와 경도는 함께 입력해야 합니다.")
    public boolean hasCompleteCoordinates() {
        return (latitude == null) == (longitude == null);
    }
}
