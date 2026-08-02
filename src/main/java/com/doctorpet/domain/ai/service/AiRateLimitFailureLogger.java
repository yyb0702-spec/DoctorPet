package com.doctorpet.domain.ai.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiRateLimitFailureLogger {

    private static final long LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final LongSupplier nanoTime;
    private final AtomicReference<AiRateLimitLogState> state =
            new AtomicReference<>(AiRateLimitLogState.recovered());

    public AiRateLimitFailureLogger() {
        this(System::nanoTime);
    }

    AiRateLimitFailureLogger(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public void logFailure(RuntimeException exception) {
        long now = nanoTime.getAsLong();

        while (true) {
            AiRateLimitLogState current = state.get();
            if (current.failureActive() && now - current.nextLogAllowedAtNanos() < 0L) {
                AiRateLimitLogState suppressed = current.suppressFailure();
                if (state.compareAndSet(current, suppressed)) {
                    return;
                }
                continue;
            }

            AiRateLimitLogState logged = AiRateLimitLogState.failed(
                    now + LOG_INTERVAL_NANOS
            );
            if (state.compareAndSet(current, logged)) {
                log.warn(
                        "Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다. suppressedCount={}",
                        current.suppressedFailureCount(),
                        exception
                );
                return;
            }
        }
    }

    public void logRecovery() {
        while (true) {
            AiRateLimitLogState current = state.get();
            if (!current.failureActive()) {
                return;
            }

            if (state.compareAndSet(current, AiRateLimitLogState.recovered())) {
                log.info(
                        "Redis Rate Limit 확인이 복구되었습니다. suppressedCount={}",
                        current.suppressedFailureCount()
                );
                return;
            }
        }
    }
}
