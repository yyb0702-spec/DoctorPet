package com.doctorpet.global.gateway.ai.openai;

import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import java.util.List;

/**
 * Tool 인자와 최종 응답에 공통으로 반복되는 OpenAI 분석 5필드.
 *
 * <p>OpenAI JSON에는 모델·프롬프트·토큰 정보가 없으므로 먼저 이 객체로 분석값만 표현하고, 서버 설정과
 * Responses API usage를 결합하는 시점에 제공자 중립 {@link AiAnalysisResult}로 변환한다.
 */
record OpenAiAnalysisFields(
        List<String> possibleFocusAreas,
        List<String> requiredCapabilities,
        UrgencyLevel urgencyLevel,
        List<String> preVisitCheckpoints,
        boolean recommendVetVisit
) {

    AiAnalysisResult toResult(
            String model,
            String promptVersion,
            Integer promptTokens,
            Integer completionTokens
    ) {
        // 앞의 5개는 모델 출력이고 뒤의 4개는 서버 설정·OpenAI usage에서 얻은 운영 관측값이다.
        return new AiAnalysisResult(
                possibleFocusAreas,
                requiredCapabilities,
                urgencyLevel,
                preVisitCheckpoints,
                recommendVetVisit,
                model,
                promptVersion,
                promptTokens,
                completionTokens
        );
    }
}
