package com.doctorpet.domain.ai.model;

import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.AiFocusArea;
import com.doctorpet.global.gateway.ai.dto.AiPreVisitCheckpoint;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import java.util.List;

/** PRD §7에서 확정한 AI 구조화 출력 5필드. */
public record AiStructuredResult(
        List<String> possibleFocusAreas,
        List<String> requiredCapabilities,
        UrgencyLevel urgencyLevel,
        List<String> preVisitCheckpoints,
        boolean recommendVetVisit
) {

    public AiStructuredResult {
        possibleFocusAreas = List.copyOf(possibleFocusAreas);
        requiredCapabilities = List.copyOf(requiredCapabilities);
        preVisitCheckpoints = List.copyOf(preVisitCheckpoints);
    }

    public static AiStructuredResult from(AiAnalysisResult result) {
        return new AiStructuredResult(
                result.possibleFocusAreas().stream()
                        .map(AiFocusArea::displayNameOf)
                        .toList(),
                result.requiredCapabilities(),
                result.urgencyLevel(),
                result.preVisitCheckpoints().stream()
                        .map(AiPreVisitCheckpoint::guidanceOf)
                        .toList(),
                result.recommendVetVisit()
        );
    }
}
