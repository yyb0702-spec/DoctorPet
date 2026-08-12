package com.doctorpet.global.gateway.ai.dto;

import java.util.List;
import java.util.Objects;

public record AiHospitalRecommendationResult(
        Long hospitalId,
        int recommendationScore,
        List<AiRecommendationEvidenceResult> evidence
) {

    public AiHospitalRecommendationResult {
        Objects.requireNonNull(hospitalId, "추천 병원 ID는 null일 수 없습니다.");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "추천 근거는 null일 수 없습니다."));
    }
}
