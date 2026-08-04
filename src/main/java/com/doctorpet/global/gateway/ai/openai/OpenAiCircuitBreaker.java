package com.doctorpet.global.gateway.ai.openai;

import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 외부 OpenAI 장애가 반복될 때 제한 시간 동안 호출을 차단하는 경량 Circuit Breaker. */
final class OpenAiCircuitBreaker {

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;
    private int consecutiveFailures;
    private long openedAt;
    private boolean halfOpenProbeInProgress;

    OpenAiCircuitBreaker(int failureThreshold, long openMs) {
        this(failureThreshold, openMs, System::nanoTime);
    }

    OpenAiCircuitBreaker(int failureThreshold, long openMs, LongSupplier nanoTime) {
        if (failureThreshold <= 0 || openMs <= 0) {
            throw new IllegalArgumentException("Circuit Breaker 설정값은 양수여야 합니다.");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = TimeUnit.MILLISECONDS.toNanos(openMs);
        this.nanoTime = nanoTime;
    }

    synchronized void beforeCall() {
        // CLOSED: 아직 연속 실패가 임계값 미만이므로 호출을 그대로 허용한다.
        if (consecutiveFailures < failureThreshold) {
            return;
        }
        long now = nanoTime.getAsLong();
        // OPEN 또는 이미 HALF_OPEN 시험 호출이 진행 중이면 새 외부 요청을 차단한다.
        if (now - openedAt < openNanos || halfOpenProbeInProgress) {
            throw new AiGatewayException(
                    AiGatewayFailureReason.TEMPORARY_UNAVAILABLE,
                    "OpenAI Circuit Breaker가 열려 있습니다."
            );
        }
        // 차단 시간이 지난 첫 요청 하나만 HALF_OPEN 시험 호출로 허용한다.
        halfOpenProbeInProgress = true;
    }

    synchronized void onSuccess() {
        // 정상 호출 또는 HALF_OPEN 시험 성공: 회로를 CLOSED 상태로 완전히 초기화한다.
        consecutiveFailures = 0;
        openedAt = 0L;
        halfOpenProbeInProgress = false;
    }

    synchronized void onFailure() {
        // HALF_OPEN 시험 실패도 새 실패로 기록하고 차단 시간을 현재 시점부터 다시 시작한다.
        consecutiveFailures++;
        halfOpenProbeInProgress = false;
        if (consecutiveFailures >= failureThreshold) {
            openedAt = nanoTime.getAsLong();
        }
    }
}
