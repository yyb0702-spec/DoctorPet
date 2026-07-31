package com.doctorpet.domain.ai.dto.request;

import com.doctorpet.domain.pet.entity.PetSpecies;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AiConsultationRequest(
        @NotBlank(message = "증상 내용은 필수입니다.")
        @Size(max = 1000, message = "증상 내용은 1,000자를 초과할 수 없습니다.")
        String symptomText,

        @NotNull(message = "축종은 필수입니다.")
        PetSpecies species,

        String region
) {
}
