package com.doctorpet.global.gateway.ai.openai;

import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import com.doctorpet.domain.hospital.model.HospitalSearchSort;
import com.doctorpet.global.gateway.ai.tool.AiHospitalSearchToolCall;
import java.util.List;

/**
 * OpenAI function_call의 {@code arguments} JSON 문자열과 일치하는 내부 DTO.
 *
 * <p>OpenAI 스키마는 분석 5필드와 검색 조건이 평평한 구조지만, 서버 공통 Tool 계약은 분석을
 * {@link AiHospitalSearchToolCall#analysis()}로 묶는다. 따라서 이 객체가 두 구조 사이의 변환 경계가 된다.
 */
record OpenAiToolArguments(
        List<String> possibleFocusAreas,
        List<String> requiredCapabilities,
        UrgencyLevel urgencyLevel,
        List<String> preVisitCheckpoints,
        Boolean recommendVetVisit,
        Boolean emergency,
        Boolean nightCare,
        Boolean openNow,
        HospitalSearchSort sort
) {

    AiHospitalSearchToolCall toToolCall(
            OpenAiProperties properties,
            int promptTokens,
            int completionTokens
    ) {
        // 2차 호출 전 Tool이 실패해도 이미 사용한 1차 Responses API usage는 상담 이력에 남겨야 한다.
        OpenAiAnalysisFields analysis = new OpenAiAnalysisFields(
                possibleFocusAreas,
                requiredCapabilities,
                urgencyLevel,
                preVisitCheckpoints,
                recommendVetVisit
        );
        return new AiHospitalSearchToolCall(
                analysis.toResult(
                        properties.getModel(),
                        properties.getPromptVersion(),
                        promptTokens,
                        completionTokens
                ),
                emergency,
                nightCare,
                openNow,
                sort
        );
    }
}
