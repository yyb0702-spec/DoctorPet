package com.doctorpet.global.gateway.ai;

/**
 * AI 게이트웨이 호출 실패.
 *
 * <p>공급자 오류를 공통 실패 사유로 변환하며, 사용자 응답과 fallback 여부는 상위 서비스가 결정한다.
 */
public class AiGatewayException extends RuntimeException {

    private final AiGatewayFailureReason failureReason;

    public AiGatewayException(AiGatewayFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public AiGatewayException(AiGatewayFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public AiGatewayFailureReason getFailureReason() {
        return failureReason;
    }
}
