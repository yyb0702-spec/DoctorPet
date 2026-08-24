package com.doctorpet.global.gateway.ai.dto;

import java.util.Objects;

public record AiRecommendationEvidenceResult(
        AiRecommendationEvidenceType type,
        String value
) {

    public AiRecommendationEvidenceResult {
        Objects.requireNonNull(type, "추천 근거 유형은 null일 수 없습니다.");
        Objects.requireNonNull(value, "추천 근거 값은 null일 수 없습니다.");
    }
}
