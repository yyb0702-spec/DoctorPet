package com.doctorpet.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Level 3 — 실제 Redis로 검증(docs/testing/verification-guide.md, SA 부록 B). 탈퇴 회원 Access
 * Token 블랙리스트(리뷰 지적 P1 대응)는 JwtAuthenticationFilter가 매 요청마다 참조하는 실제
 * Redis 키 존재 여부 확인이라, Mockito 슬라이스로는 EXISTS 동작 자체를 검증할 수 없다.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost:8080/api/auth/verify-email",
        "mail.password-reset.base-url=http://localhost:3000/reset-password"
})
class RefreshTokenRepositoryIntegrationTest {

    private static final Long MEMBER_ID = 1L;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete("withdrawn:" + MEMBER_ID);
    }

    @Test
    void 블랙리스트에_등록하기_전에는_isBlacklisted가_false다() {
        assertThat(refreshTokenRepository.isBlacklisted(MEMBER_ID)).isFalse();
    }

    @Test
    void 블랙리스트에_등록하면_isBlacklisted가_true다() {
        refreshTokenRepository.blacklistMember(MEMBER_ID, Duration.ofMinutes(1));

        assertThat(refreshTokenRepository.isBlacklisted(MEMBER_ID)).isTrue();
    }
}
