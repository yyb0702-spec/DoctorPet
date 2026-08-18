package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.config.AuthRateLimitProperties;
import com.doctorpet.domain.member.exception.AuthRateLimitStorageException;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.AuthRateLimitRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

    @Mock
    private AuthRateLimitRepository repository;

    @Mock
    private ClientIpResolver clientIpResolver;

    private AuthRateLimitProperties properties;
    private AuthRateLimiter rateLimiter;
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        properties = new AuthRateLimitProperties();
        rateLimiter = new AuthRateLimiter(repository, properties, clientIpResolver);
        given(clientIpResolver.resolve(eq(request), any())).willReturn("203.0.113.10");
    }

    @Test
    @DisplayName("한도 이내면 통과한다")
    void check_withinLimit_allowed() {
        given(repository.increment(startsWith("auth:rate-limit:SIGNUP:ip:"), any()))
                .willReturn((long) properties.getSignupPerIpPerHour());

        assertThatCode(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도를 초과하면 MEMBER_011(RATE_LIMIT_EXCEEDED)을 던진다")
    void check_exceedsLimit_throws() {
        given(repository.increment(startsWith("auth:rate-limit:SIGNUP:ip:"), any()))
                .willReturn((long) properties.getSignupPerIpPerHour() + 1);

        assertThatThrownBy(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("SIGNUP·VERIFY_EMAIL_RESEND·PASSWORD_RESET_REQUEST는 서로 독립된 카운터를 쓴다 — 한쪽 한도 초과가 다른 쪽에 영향을 주지 않는다")
    void check_differentActions_useIndependentCounters() {
        given(repository.increment(startsWith("auth:rate-limit:SIGNUP:ip:"), any())).willReturn(999L);
        given(repository.increment(startsWith("auth:rate-limit:VERIFY_EMAIL_RESEND:ip:"), any())).willReturn(1L);

        assertThatThrownBy(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .isInstanceOf(ServiceException.class);
        assertThatCode(() -> rateLimiter.check(request, AuthRateLimitAction.VERIFY_EMAIL_RESEND))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("저장소 장애 시 요청을 차단하지 않는다(fail-open)")
    void check_storageFailure_failsOpen() {
        given(repository.increment(any(), any()))
                .willThrow(new AuthRateLimitStorageException("redis unavailable"));

        assertThatCode(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("저장소 장애가 아닌 예상하지 못한 오류는 fail-open으로 숨기지 않는다")
    void check_unexpectedRuntimeException_propagated() {
        given(repository.increment(any(), any()))
                .willThrow(new IllegalStateException("unexpected bug"));

        assertThatThrownBy(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected bug");
    }

    @Test
    @DisplayName("한도 초과로 예외를 던지기 전에 이미 카운터는 증가한 상태다 — 초과 이후 재시도도 계속 거부된다")
    void check_afterExceeded_stillRejectsSubsequentAttempts() {
        given(repository.increment(startsWith("auth:rate-limit:SIGNUP:ip:"), any()))
                .willReturn((long) properties.getSignupPerIpPerHour() + 1)
                .willReturn((long) properties.getSignupPerIpPerHour() + 2);

        assertThatThrownBy(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> rateLimiter.check(request, AuthRateLimitAction.SIGNUP))
                .isInstanceOf(ServiceException.class);

        verify(repository, never()).increment(startsWith("auth:rate-limit:VERIFY_EMAIL_RESEND:ip:"), any());
    }
}
