package com.doctorpet.domain.ai.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiRateLimitFailureLogger {

    private static final long LOG_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final LongSupplier currentTimeMillis;
    private final AtomicReference<AiRateLimitLogState> state =
            new AtomicReference<>(AiRateLimitLogState.recovered());

    public AiRateLimitFailureLogger() {
        this(System::currentTimeMillis);
    }

    AiRateLimitFailureLogger(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = currentTimeMillis;
    }

    public void logFailure(RuntimeException exception) {
        long now = currentTimeMillis.getAsLong();

        while (true) {
            AiRateLimitLogState current = state.get();
            if (now < current.nextLogAllowedAt()) {
                AiRateLimitLogState suppressed = current.suppressFailure();
                if (state.compareAndSet(current, suppressed)) {
                    return;
                }
                continue;
            }

            AiRateLimitLogState logged = AiRateLimitLogState.failed(
                    now + LOG_INTERVAL_MILLIS
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
