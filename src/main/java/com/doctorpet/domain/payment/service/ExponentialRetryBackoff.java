package com.doctorpet.domain.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 지수 백오프 기본 구현(SA §9-4). initial × 2^(attempt-1) ms 대기(예: 500 → 1000 → 2000).
 * 초기 대기값은 설정(payment.charge.backoff-initial-ms)으로 주입하며 기본값 500ms를 둔다.
 *
 * <p>계산된 대기가 {@code maxWaitMs}(호출부가 넘긴 남은 백오프 예산)를 넘으면 그 상한까지만 대기한다 —
 * 지수 백오프의 HTTP 스레드 동기 점유를 상한으로 묶기 위함이다(#85 데드라인 캡).
 */
@Component
public class ExponentialRetryBackoff implements RetryBackoff {

    private final long initialMs;

    public ExponentialRetryBackoff(@Value("${payment.charge.backoff-initial-ms:500}") long initialMs) {
        this.initialMs = initialMs;
    }

    @Override
    public long pause(int attempt, long maxWaitMs) {
        if (maxWaitMs <= 0) {
            return 0;
        }
        long computedWaitMs = initialMs * (1L << (attempt - 1));
        long waitMs = Math.min(computedWaitMs, maxWaitMs);
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return waitMs;
    }
}
