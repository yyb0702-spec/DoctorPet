package com.doctorpet.global.gateway.ai.openai;

import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 외부 OpenAI 장애가 반복될 때 제한 시간 동안 호출을 차단하는 경량 Circuit Breaker.
 *
 * <p>CLOSED에서는 요청 완료 순서로 연속 실패를 계산한다. OPEN 또는 HALF_OPEN으로 전환된 뒤에는 이전
 * CLOSED 요청의 늦은 완료가 현재 회로 상태를 바꾸지 않으며, HALF_OPEN 시험 요청 소유자의 결과만 회복 또는
 * 재차단을 결정한다.
 */
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

    synchronized boolean beforeCall() {
        // CLOSED: 아직 연속 실패가 임계값 미만이므로 호출을 그대로 허용한다.
        if (consecutiveFailures < failureThreshold) {
            return false;
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
        return true;
    }

    synchronized void onSuccess(boolean halfOpenProbe) {
        // OPEN/HALF_OPEN 전환 전에 시작한 일반 요청의 늦은 성공은 진행 중인 시험 요청을 대신할 수 없다.
        if (!halfOpenProbe && consecutiveFailures >= failureThreshold) {
            return;
        }
        consecutiveFailures = 0;
        openedAt = 0L;
        halfOpenProbeInProgress = false;
    }

    synchronized void onFailure(boolean halfOpenProbe) {
        // OPEN/HALF_OPEN 전환 전에 시작한 일반 요청의 늦은 실패도 현재 시험 요청의 소유권을 해제하거나
        // 차단 시간을 다시 시작하게 하지 않는다.
        if (!halfOpenProbe && consecutiveFailures >= failureThreshold) {
            return;
        }
        // HALF_OPEN 시험 실패도 새 실패로 기록하고 차단 시간을 현재 시점부터 다시 시작한다.
        consecutiveFailures++;
        halfOpenProbeInProgress = false;
        if (consecutiveFailures >= failureThreshold) {
            openedAt = nanoTime.getAsLong();
        }
    }

    synchronized void onIgnoredFailure(boolean halfOpenProbe) {
        // INVALID_RESPONSE나 Tool 실행 실패는 공급자 가용성 실패로 집계하지 않는다. 다만 현재 요청이
        // HALF_OPEN 시험 요청이었다면 시험 중 표시를 해제해 다음 복구 요청이 영구 차단되지 않게 한다.
        // CLOSED 상태에서 시작한 다른 요청이 동시 HALF_OPEN 시험의 표시를 지우지 않도록 소유 여부를 받는다.
        if (halfOpenProbe) {
            halfOpenProbeInProgress = false;
        }
    }
}
