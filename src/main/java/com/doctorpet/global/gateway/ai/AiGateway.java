package com.doctorpet.global.gateway.ai;

import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.AiGatewayConsultationResult;
import com.doctorpet.global.gateway.ai.tool.AiToolExecutor;

/**
 * AI 상담의 LLM 연동 추상화.
 *
 * <p>제공자별 요청·응답 형식을 공통 DTO로 변환하며, 상담 안전 정책과 fallback 처리는
 * 상위 AI 상담 서비스가 담당한다(SA §9-5).
 */
public interface AiGateway {

    /**
     * 증상과 축종을 바탕으로 병원 검색에 사용할 구조화 결과를 반환한다.
     *
     * @throws AiGatewayException 외부 AI 호출 또는 구조화 응답 처리에 실패한 경우
     */
    AiAnalysisResult analyze(AiAnalysisRequest request);

    /**
     * 모델이 Tool 호출 여부를 결정하는 상담 흐름을 실행한다.
     *
     * <p>기존 Fake 구현은 구조화 분석만 반환하고 상위 서비스의 임시 검색 흐름을 유지한다.
     * 실제 Tool Calling 구현은 이 메서드를 재정의해 모델의 호출을 {@code toolExecutor}로 실행한다.
     */
    default AiGatewayConsultationResult consult(
            AiAnalysisRequest request,
            AiToolExecutor toolExecutor
    ) {
        return AiGatewayConsultationResult.withoutToolCalling(analyze(request));
    }
}
