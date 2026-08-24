package com.doctorpet.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.member.exception.AuthRateLimitStorageException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitRepositoryUnitTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private AuthRateLimitRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AuthRateLimitRepository(redisTemplate);
    }

    @Test
    @DisplayName("Redis 실행 예외를 rate limit 저장소 예외로 변환한다")
    void increment_redisFailure_throwsStorageException() {
        IllegalStateException cause = new IllegalStateException("redis unavailable");
        given(redisTemplate.execute(any(), anyList(), any()))
                .willThrow(cause);

        assertThatThrownBy(() -> repository.increment("rate-limit:key", Duration.ofMinutes(1)))
                .isInstanceOf(AuthRateLimitStorageException.class)
                .hasCause(cause);
    }

    @Test
    @DisplayName("Redis 카운터 결과가 없으면 rate limit 저장소 예외를 던진다")
    void increment_nullResult_throwsStorageException() {
        given(redisTemplate.execute(any(), anyList(), any()))
                .willReturn(null);

        assertThatThrownBy(() -> repository.increment("rate-limit:key", Duration.ofMinutes(1)))
                .isInstanceOf(AuthRateLimitStorageException.class)
                .hasMessage("인증메일 rate limit 카운터 결과가 없습니다.");
    }
}
