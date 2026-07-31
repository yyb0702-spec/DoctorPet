package com.doctorpet.global.gateway.ai;

/**
 * AI 제공자와 무관한 실패 분류.
 */
public enum AiGatewayFailureReason {

    TIMEOUT,
    TEMPORARY_UNAVAILABLE,
    INVALID_RESPONSE
}
