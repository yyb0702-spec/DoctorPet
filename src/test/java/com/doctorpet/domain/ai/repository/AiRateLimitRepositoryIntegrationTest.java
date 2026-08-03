package com.doctorpet.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Level 3 — 실제 Redis에서 AI 상담 카운터 증가와 TTL 설정을 검증한다. */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost:8080/api/auth/verify-email",
        "mail.password-reset.base-url=http://localhost:3000/reset-password"
})
class AiRateLimitRepositoryIntegrationTest {

    private static final String KEY = "test:ai-consultation:rate-limit";

    @Autowired
    private AiRateLimitRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("첫 요청부터 카운터를 원자적으로 증가시키고 TTL을 설정한다")
    void increment_increasesCounterAndSetsTtl() {
        long first = repository.increment(KEY, Duration.ofMinutes(1));
        long second = repository.increment(KEY, Duration.ofMinutes(1));

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
        assertThat(redisTemplate.getExpire(KEY)).isPositive();
    }
}
