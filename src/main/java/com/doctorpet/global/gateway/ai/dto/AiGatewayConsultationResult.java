package com.doctorpet.global.gateway.ai.dto;

import java.util.List;
import java.util.Objects;

/** 제공자 중립 상담 결과와 Tool Calling 실행 여부 및 병원 추천 결과를 담는다. */
public record AiGatewayConsultationResult(
        AiAnalysisResult analysis,
        boolean locationRequired,
        boolean toolCallingHandled,
        boolean toolCalled,
        List<AiHospitalRecommendationResult> recommendations
) {

    public AiGatewayConsultationResult {
        Objects.requireNonNull(analysis, "AI 구조화 결과는 null일 수 없습니다.");
        recommendations = List.copyOf(
                Objects.requireNonNull(recommendations, "AI 병원 추천 결과는 null일 수 없습니다."));
    }

    public AiGatewayConsultationResult(
            AiAnalysisResult analysis,
            boolean locationRequired,
            boolean toolCallingHandled,
            boolean toolCalled
    ) {
        this(analysis, locationRequired, toolCallingHandled, toolCalled, List.of());
    }

    public static AiGatewayConsultationResult withoutToolCalling(AiAnalysisResult analysis) {
        return new AiGatewayConsultationResult(analysis, false, false, false, List.of());
    }
}
