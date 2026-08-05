package com.doctorpet.global.gateway.ai.tool;

@FunctionalInterface
public interface AiToolExecutor {

    /**
     * 모델이 생성한 병원 검색 인자를 서버 검색 계약으로 실행한다.
     * 반환 JSON은 OpenAI 2차 요청의 {@code function_call_output}으로 그대로 전달된다.
     */
    String searchNearbyVets(AiHospitalSearchToolCall call);
}
