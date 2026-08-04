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
 *
 * save/matches/rotateIfMatches(#123 해시 저장 전환)도 여기서 함께 검증한다 — 실제 Redis에
 * 저장된 값이 원문이 아니라 해시인지, 원문 없이는(matches) CAS가 통과하지 않는지는 Mockito
 * 슬라이스로는 확인할 수 없는 저장소 자체의 계약이다.
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
        redisTemplate.delete("refresh:" + MEMBER_ID);
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

    @Test
    void save는_원문이_아니라_해시를_저장한다() {
        String rawToken = "raw-refresh-token-value";

        refreshTokenRepository.save(MEMBER_ID, rawToken, Duration.ofMinutes(1));

        String stored = redisTemplate.opsForValue().get("refresh:" + MEMBER_ID);
        assertThat(stored).isNotEqualTo(rawToken);
        assertThat(stored).hasSize(64); // SHA-256 hex 인코딩은 항상 64자
    }

    @Test
    void matches는_저장된_해시와_일치하는_원문에_대해서만_true를_반환한다() {
        String rawToken = "raw-refresh-token-value";
        refreshTokenRepository.save(MEMBER_ID, rawToken, Duration.ofMinutes(1));

        assertThat(refreshTokenRepository.matches(MEMBER_ID, rawToken)).isTrue();
        assertThat(refreshTokenRepository.matches(MEMBER_ID, "wrong-token")).isFalse();
    }

    @Test
    void rotateIfMatches는_저장된_해시와_일치하는_원문을_제시했을_때만_회전에_성공한다() {
        String oldToken = "old-refresh-token";
        String newToken = "new-refresh-token";
        refreshTokenRepository.save(MEMBER_ID, oldToken, Duration.ofMinutes(1));

        boolean rotated = refreshTokenRepository.rotateIfMatches(MEMBER_ID, oldToken, newToken, Duration.ofMinutes(1));

        assertThat(rotated).isTrue();
        assertThat(refreshTokenRepository.matches(MEMBER_ID, newToken)).isTrue();
        assertThat(refreshTokenRepository.matches(MEMBER_ID, oldToken)).isFalse();
    }
}
