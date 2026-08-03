package com.doctorpet.global.gateway.ai.dto;

import com.doctorpet.domain.pet.entity.PetSpecies;

import java.util.Objects;

/**
 * AI 게이트웨이에 전달하는 제공자 중립 요청.
 */
public record AiAnalysisRequest(
        String symptomText,
        PetSpecies species
) {

    public AiAnalysisRequest {
        Objects.requireNonNull(symptomText, "증상 내용은 null일 수 없습니다.");
        Objects.requireNonNull(species, "축종은 null일 수 없습니다.");
    }
}
