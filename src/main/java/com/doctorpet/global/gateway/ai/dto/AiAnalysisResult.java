package com.doctorpet.global.gateway.ai.dto;

import java.util.List;
import java.util.Objects;

/**
 * PRD §7의 AI 구조화 출력 5필드와 호출 관측값을 담는 제공자 중립 결과.
 *
 * <p>Fake 또는 LLM 미호출 경로에서는 모델·프롬프트·토큰 값이 {@code null}일 수 있다.
 */
public record AiAnalysisResult(
        List<String> possibleFocusAreas,
        List<String> requiredCapabilities,
        UrgencyLevel urgencyLevel,
        List<String> preVisitCheckpoints,
        boolean recommendVetVisit,
        String model,
        String promptVersion,
        Integer promptTokens,
        Integer completionTokens
) {

    public AiAnalysisResult {
        possibleFocusAreas = List.copyOf(
                Objects.requireNonNull(possibleFocusAreas, "중점 확인 영역은 null일 수 없습니다."));
        requiredCapabilities = List.copyOf(
                Objects.requireNonNull(requiredCapabilities, "필수 진료역량은 null일 수 없습니다."));
        Objects.requireNonNull(urgencyLevel, "긴급도는 null일 수 없습니다.");
        preVisitCheckpoints = List.copyOf(
                Objects.requireNonNull(preVisitCheckpoints, "방문 전 확인사항은 null일 수 없습니다."));
    }

}
