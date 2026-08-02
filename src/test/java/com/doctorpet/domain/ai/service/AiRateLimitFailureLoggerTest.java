package com.doctorpet.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.StringUtils;

@ExtendWith(OutputCaptureExtension.class)
class AiRateLimitFailureLoggerTest {

    @Test
    @DisplayName("동시 Redis 실패는 제한 시간에 경고 로그를 한 번만 남긴다")
    void logFailure_concurrentFailures_logsOnce(CapturedOutput output) throws Exception {
        AtomicLong nanoTime = new AtomicLong(1_000L);
        AiRateLimitFailureLogger failureLogger = new AiRateLimitFailureLogger(nanoTime::get);
        int requestCount = 20;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    failureLogger.logFailure(new IllegalStateException("redis unavailable"));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(StringUtils.countOccurrencesOf(
                output.getOut(),
                "Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다."
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("제한 시간이 지나면 억제된 실패 횟수와 함께 다시 경고한다")
    void logFailure_afterInterval_logsSuppressedCount(CapturedOutput output) {
        AtomicLong nanoTime = new AtomicLong(1_000L);
        AiRateLimitFailureLogger failureLogger = new AiRateLimitFailureLogger(nanoTime::get);

        failureLogger.logFailure(new IllegalStateException("first"));
        failureLogger.logFailure(new IllegalStateException("second"));
        failureLogger.logFailure(new IllegalStateException("third"));
        nanoTime.addAndGet(Duration.ofMinutes(1).toNanos());
        failureLogger.logFailure(new IllegalStateException("after interval"));

        assertThat(StringUtils.countOccurrencesOf(
                output.getOut(),
                "Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다."
        )).isEqualTo(2);
        assertThat(output).contains("suppressedCount=2");
    }

    @Test
    @DisplayName("복구 시 상태 전체를 초기화하고 다음 장애는 즉시 경고한다")
    void logRecovery_afterSuppressedFailure_resetsEntireState(CapturedOutput output) {
        AtomicLong nanoTime = new AtomicLong(1_000L);
        AiRateLimitFailureLogger failureLogger = new AiRateLimitFailureLogger(nanoTime::get);

        failureLogger.logFailure(new IllegalStateException("first"));
        failureLogger.logFailure(new IllegalStateException("suppressed"));
        failureLogger.logRecovery();
        failureLogger.logRecovery();
        failureLogger.logFailure(new IllegalStateException("second"));

        assertThat(StringUtils.countOccurrencesOf(
                output.getOut(),
                "Redis Rate Limit 확인이 복구되었습니다."
        )).isEqualTo(1);
        assertThat(output).contains("복구되었습니다. suppressedCount=1");
        assertThat(StringUtils.countOccurrencesOf(
                output.getOut(),
                "Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다."
        )).isEqualTo(2);
    }

    @Test
    @DisplayName("나노 시각값이 음수여도 정상 상태의 첫 장애는 즉시 경고한다")
    void logFailure_negativeNanoTimeWhileRecovered_logsImmediately(CapturedOutput output) {
        AtomicLong nanoTime = new AtomicLong(-1_000L);
        AiRateLimitFailureLogger failureLogger = new AiRateLimitFailureLogger(nanoTime::get);

        failureLogger.logFailure(new IllegalStateException("first"));

        assertThat(output)
                .contains("Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다.");
    }

    @Test
    @DisplayName("나노 시각값이 오버플로되어도 제한 시간 경과를 올바르게 판단한다")
    void logFailure_nanoTimeOverflow_respectsInterval(CapturedOutput output) {
        AtomicLong nanoTime = new AtomicLong(Long.MAX_VALUE - 1_000L);
        AiRateLimitFailureLogger failureLogger = new AiRateLimitFailureLogger(nanoTime::get);

        failureLogger.logFailure(new IllegalStateException("first"));
        failureLogger.logFailure(new IllegalStateException("suppressed"));
        nanoTime.addAndGet(Duration.ofMinutes(1).toNanos());
        failureLogger.logFailure(new IllegalStateException("after interval"));

        assertThat(StringUtils.countOccurrencesOf(
                output.getOut(),
                "Redis Rate Limit 확인에 실패하여 AI 상담 요청을 허용합니다."
        )).isEqualTo(2);
    }
}
