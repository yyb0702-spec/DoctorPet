package com.doctorpet.global.gateway.ai.openai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.global.gateway.ai.AiGatewayException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenAiCircuitBreakerTest {

    @Test
    @DisplayName("연속 실패 임계에 도달하면 호출을 차단하고 제한 시간 뒤 한 번의 복구 호출을 허용한다")
    void circuitBreaker_opensAndAllowsHalfOpenProbe() {
        AtomicLong now = new AtomicLong();
        OpenAiCircuitBreaker circuitBreaker = new OpenAiCircuitBreaker(2, 1000, now::get);

        circuitBreaker.onFailure();
        circuitBreaker.beforeCall();
        circuitBreaker.onFailure();

        assertThatThrownBy(circuitBreaker::beforeCall)
                .isInstanceOf(AiGatewayException.class);

        now.set(1_000_000_000L);
        assertThatCode(circuitBreaker::beforeCall).doesNotThrowAnyException();
        assertThatThrownBy(circuitBreaker::beforeCall)
                .isInstanceOf(AiGatewayException.class);

        circuitBreaker.onSuccess();
        assertThatCode(circuitBreaker::beforeCall).doesNotThrowAnyException();
    }
}
