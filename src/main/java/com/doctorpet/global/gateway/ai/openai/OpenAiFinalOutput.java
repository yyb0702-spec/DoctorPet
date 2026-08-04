package com.doctorpet.global.gateway.ai.openai;

import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import java.util.List;

/** OpenAI 최종 응답용 JSON Schema와 일치하는 내부 DTO. 외부 API 응답 DTO로 직접 노출하지 않는다. */
record OpenAiFinalOutput(
        List<String> possibleFocusAreas,
        List<String> requiredCapabilities,
        UrgencyLevel urgencyLevel,
        List<String> preVisitCheckpoints,
        Boolean recommendVetVisit,
        Boolean locationRequired
) {

    OpenAiAnalysisFields analysis() {
        // 최종 안내 필드를 제외하고 Tool 인자와 공유하는 분석 5필드만 분리한다.
        return new OpenAiAnalysisFields(
                possibleFocusAreas,
                requiredCapabilities,
                urgencyLevel,
                preVisitCheckpoints,
                recommendVetVisit
        );
    }
}
