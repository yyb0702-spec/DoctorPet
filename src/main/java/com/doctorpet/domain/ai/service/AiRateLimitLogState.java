package com.doctorpet.domain.ai.service;

record AiRateLimitLogState(
        boolean failureActive,
        long nextLogAllowedAt,
        long suppressedFailureCount
) {

    static AiRateLimitLogState recovered() {
        return new AiRateLimitLogState(false, 0L, 0L);
    }

    static AiRateLimitLogState failed(long nextLogAllowedAt) {
        return new AiRateLimitLogState(true, nextLogAllowedAt, 0L);
    }

    AiRateLimitLogState suppressFailure() {
        return new AiRateLimitLogState(
                true,
                nextLogAllowedAt,
                suppressedFailureCount + 1
        );
    }
}
