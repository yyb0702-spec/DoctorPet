package com.doctorpet.global.gateway.ai;

import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;

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
}
