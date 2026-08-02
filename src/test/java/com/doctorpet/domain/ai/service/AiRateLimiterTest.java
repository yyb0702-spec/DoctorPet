package com.doctorpet.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.ai.config.AiRateLimitProperties;
import com.doctorpet.domain.ai.exception.AiErrorCode;
import com.doctorpet.domain.ai.repository.AiRateLimitRepository;
import com.doctorpet.global.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiRateLimiterTest {

    @Mock
    private AiRateLimitRepository repository;

    private AiRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new AiRateLimiter(repository, new AiRateLimitProperties());
    }

    @Test
    @DisplayName("로그인 사용자의 분당 여섯 번째 요청을 거부한다")
    void check_authenticatedSixthRequest_rejected() {
        given(repository.increment(startsWith("ai-consultation:rate-limit:member:minute:7"), any()))
                .willReturn(6L);

        assertThatThrownBy(() -> rateLimiter.check(7L, "203.0.113.10"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(AiErrorCode.RATE_LIMIT_EXCEEDED);

        verify(repository, never()).increment(
                startsWith("ai-consultation:rate-limit:anonymous:"), any());
    }

    @Test
    @DisplayName("비로그인 사용자의 분당 세 번째와 일일 서른 번째 요청을 허용한다")
    void check_anonymousAtLimits_allowed() {
        given(repository.increment(startsWith("ai-consultation:rate-limit:anonymous:minute:"), any()))
                .willReturn(3L);
        given(repository.increment(startsWith("ai-consultation:rate-limit:anonymous:day:"), any()))
                .willReturn(30L);

        assertThatCode(() -> rateLimiter.check(null, "203.0.113.10"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 장애 시 AI 상담 요청을 차단하지 않는다")
    void check_redisFailure_failsOpen() {
        given(repository.increment(any(), any()))
                .willThrow(new IllegalStateException("redis unavailable"));

        assertThatCode(() -> rateLimiter.check(null, "203.0.113.10"))
                .doesNotThrowAnyException();
    }
}
