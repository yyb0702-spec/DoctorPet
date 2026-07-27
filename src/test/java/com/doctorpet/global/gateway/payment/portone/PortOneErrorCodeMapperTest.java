package com.doctorpet.global.gateway.payment.portone;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortOneErrorCodeMapperTest {

    private final PortOneErrorCodeMapper mapper = new PortOneErrorCodeMapper();

    @Test
    @DisplayName("재시도 무의미 오류 코드는 NON_RETRIABLE로 분류한다")
    void classifyNonRetriable() {
        assertEquals(GatewayFailureReason.NON_RETRIABLE, mapper.classify("CARD_LIMIT_EXCEEDED"));
        assertEquals(GatewayFailureReason.NON_RETRIABLE, mapper.classify("BILLING_KEY_EXPIRED"));
        assertEquals(GatewayFailureReason.NON_RETRIABLE, mapper.classify("CARD_SUSPENDED"));
    }

    @Test
    @DisplayName("일시 장애 오류 코드는 RETRIABLE로 분류한다")
    void classifyRetriable() {
        assertEquals(GatewayFailureReason.RETRIABLE, mapper.classify("PG_TIMEOUT"));
        assertEquals(GatewayFailureReason.RETRIABLE, mapper.classify("PG_PROVIDER_ERROR"));
    }

    @Test
    @DisplayName("코드 대소문자·공백에 무관하게 분류한다")
    void classifyCaseInsensitive() {
        assertEquals(GatewayFailureReason.NON_RETRIABLE, mapper.classify("  card_limit_exceeded "));
    }

    @Test
    @DisplayName("null·미인식 코드는 UNKNOWN으로 안전 분류한다")
    void classifyUnknown() {
        assertEquals(GatewayFailureReason.UNKNOWN, mapper.classify(null));
        assertEquals(GatewayFailureReason.UNKNOWN, mapper.classify(""));
        assertEquals(GatewayFailureReason.UNKNOWN, mapper.classify("SOME_NEW_UNMAPPED_CODE"));
    }

    @Test
    @DisplayName("HTTP 5xx·408·429는 RETRIABLE, 그 외 4xx는 UNKNOWN으로 분류한다")
    void classifyHttpStatus() {
        assertEquals(GatewayFailureReason.RETRIABLE, mapper.classifyHttpStatus(500));
        assertEquals(GatewayFailureReason.RETRIABLE, mapper.classifyHttpStatus(503));
        assertEquals(GatewayFailureReason.RETRIABLE, mapper.classifyHttpStatus(408));
        assertEquals(GatewayFailureReason.RETRIABLE, mapper.classifyHttpStatus(429));
        assertEquals(GatewayFailureReason.UNKNOWN, mapper.classifyHttpStatus(400));
        assertEquals(GatewayFailureReason.UNKNOWN, mapper.classifyHttpStatus(402));
    }
}
