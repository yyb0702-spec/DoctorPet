package com.doctorpet.global.gateway.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(circuitBreaker.beforeCall()).isFalse();
        circuitBreaker.onFailure();

        assertThatThrownBy(circuitBreaker::beforeCall)
                .isInstanceOf(AiGatewayException.class);

        now.set(1_000_000_000L);
        assertThat(circuitBreaker.beforeCall()).isTrue();
        assertThatThrownBy(circuitBreaker::beforeCall)
                .isInstanceOf(AiGatewayException.class);

        circuitBreaker.onSuccess();
        assertThat(circuitBreaker.beforeCall()).isFalse();
    }

    @Test
    @DisplayName("HALF_OPEN 시험 요청의 비집계 예외를 해제하면 다음 시험 요청을 허용한다")
    void ignoredFailure_releasesOwnedHalfOpenProbe() {
        AtomicLong now = new AtomicLong();
        OpenAiCircuitBreaker circuitBreaker = new OpenAiCircuitBreaker(2, 1000, now::get);
        circuitBreaker.onFailure();
        circuitBreaker.onFailure();
        now.set(1_000_000_000L);

        boolean halfOpenProbe = circuitBreaker.beforeCall();
        circuitBreaker.onIgnoredFailure(halfOpenProbe);

        assertThat(circuitBreaker.beforeCall()).isTrue();
    }

    @Test
    @DisplayName("일반 요청의 비집계 예외는 다른 HALF_OPEN 시험 요청 상태를 해제하지 않는다")
    void ignoredFailure_fromClosedCall_doesNotReleaseAnotherProbe() {
        AtomicLong now = new AtomicLong();
        OpenAiCircuitBreaker circuitBreaker = new OpenAiCircuitBreaker(2, 1000, now::get);
        circuitBreaker.onFailure();
        circuitBreaker.onFailure();
        now.set(1_000_000_000L);
        assertThat(circuitBreaker.beforeCall()).isTrue();

        circuitBreaker.onIgnoredFailure(false);

        assertThatThrownBy(circuitBreaker::beforeCall)
                .isInstanceOf(AiGatewayException.class);
    }

    @Test
    @DisplayName("HALF_OPEN 중 기존 요청 성공도 회복으로 인정하고 이후 실패를 첫 연속 실패로 센다")
    void successFromEarlierRequest_resetsFailuresByCompletionOrder() {
        AtomicLong now = new AtomicLong();
        OpenAiCircuitBreaker circuitBreaker = new OpenAiCircuitBreaker(2, 1000, now::get);

        assertThat(circuitBreaker.beforeCall()).isFalse(); // 장애 전에 시작한 기존 요청
        circuitBreaker.onFailure();
        circuitBreaker.onFailure();
        now.set(1_000_000_000L);
        assertThat(circuitBreaker.beforeCall()).isTrue(); // HALF_OPEN 시험 요청

        circuitBreaker.onSuccess(); // 기존 요청이 나중에 성공 완료
        circuitBreaker.onFailure(); // 시험 요청 실패는 성공 이후 첫 실패

        assertThat(circuitBreaker.beforeCall()).isFalse();
    }
}
