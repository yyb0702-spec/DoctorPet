package com.doctorpet.domain.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 지수 백오프 기본 구현(SA §9-4). initial × 2^(attempt-1) ms 대기(예: 500 → 1000 → 2000).
 * 초기 대기값은 설정(payment.charge.backoff-initial-ms)으로 주입하며 기본값 500ms를 둔다.
 */
@Component
public class ExponentialRetryBackoff implements RetryBackoff {

    private final long initialMs;

    public ExponentialRetryBackoff(@Value("${payment.charge.backoff-initial-ms:500}") long initialMs) {
        this.initialMs = initialMs;
    }

    @Override
    public void pause(int attempt) {
        long waitMs = initialMs * (1L << (attempt - 1));
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
