package com.doctorpet.domain.ai.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiRateLimitFailureLogger {

    private static final long LOG_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final LongSupplier currentTimeMillis;
    private final AtomicLong nextLogAllowedAt = new AtomicLong();
    private final AtomicLong suppressedFailureCount = new AtomicLong();
    private final AtomicBoolean failureActive = new AtomicBoolean();

    public AiRateLimitFailureLogger() {
        this(System::currentTimeMillis);
    }

    AiRateLimitFailureLogger(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = currentTimeMillis;
    }

    public void logFailure(RuntimeException exception) {
        failureActive.set(true);
        long now = currentTimeMillis.getAsLong();

        while (true) {
            long allowedAt = nextLogAllowedAt.get();
            if (now < allowedAt) {
                suppressedFailureCount.incrementAndGet();
                return;
            }
            if (nextLogAllowedAt.compareAndSet(allowedAt, now + LOG_INTERVAL_MILLIS)) {
                long suppressedCount = suppressedFailureCount.getAndSet(0L);
                log.warn(
                        "Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다. suppressedCount={}",
                        suppressedCount,
                        exception
                );
                return;
            }
        }
    }

    public void logRecovery() {
        if (!failureActive.compareAndSet(true, false)) {
            return;
        }

        long suppressedCount = suppressedFailureCount.getAndSet(0L);
        nextLogAllowedAt.set(0L);
        log.info("Redis Rate Limit 확인이 복구되었습니다. suppressedCount={}", suppressedCount);
    }
}
