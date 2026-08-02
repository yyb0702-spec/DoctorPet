package com.doctorpet.domain.ai.service;

record AiRateLimitLogState(
        boolean failureActive,
        long nextLogAllowedAtNanos,
        long suppressedFailureCount
) {

    static AiRateLimitLogState recovered() {
        return new AiRateLimitLogState(false, 0L, 0L);
    }

    static AiRateLimitLogState failed(long nextLogAllowedAtNanos) {
        return new AiRateLimitLogState(true, nextLogAllowedAtNanos, 0L);
    }

    AiRateLimitLogState suppressFailure() {
        return new AiRateLimitLogState(
                true,
                nextLogAllowedAtNanos,
                suppressedFailureCount + 1
        );
    }
}
