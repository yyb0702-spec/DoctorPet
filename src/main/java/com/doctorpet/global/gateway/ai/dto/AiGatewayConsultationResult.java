package com.doctorpet.global.gateway.ai.dto;

import java.util.Objects;

/**
 * 제공자 중립 상담 결과와 Tool Calling 실행 여부를 담는다.
 *
 * @param toolCallingHandled Gateway가 Tool Calling 오케스트레이션을 처리했는지 여부. 실제 호출 여부와는 다르다.
 * @param toolCalled 모델이 이번 상담에서 실제 병원 검색 Tool을 호출했는지 여부
 */
public record AiGatewayConsultationResult(
        AiAnalysisResult analysis,
        String message,
        boolean locationRequired,
        boolean toolCallingHandled,
        boolean toolCalled
) {

    public AiGatewayConsultationResult {
        Objects.requireNonNull(analysis, "AI 구조화 결과는 null일 수 없습니다.");
    }

    public static AiGatewayConsultationResult withoutToolCalling(AiAnalysisResult analysis) {
        return new AiGatewayConsultationResult(analysis, null, false, false, false);
    }
}
