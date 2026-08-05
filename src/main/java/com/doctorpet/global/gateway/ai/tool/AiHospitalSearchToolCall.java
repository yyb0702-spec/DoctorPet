package com.doctorpet.global.gateway.ai.tool;

import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.domain.hospital.model.HospitalSearchSort;
import java.util.Objects;

/**
 * 모델이 생성한 {@code searchNearbyVets} 호출 인자를 제공자 중립 형태로 표현한다.
 * OpenAI 전용 평면 JSON은 {@code OpenAiToolArguments}에서 이 객체로 변환된 뒤 Service의 Tool 실행기에 전달된다.
 */
public record AiHospitalSearchToolCall(
        AiAnalysisResult analysis,
        Boolean emergency,
        Boolean nightCare,
        Boolean openNow,
        HospitalSearchSort sort
) {

    public AiHospitalSearchToolCall {
        Objects.requireNonNull(analysis, "Tool 호출의 구조화 분석은 null일 수 없습니다.");
    }
}
