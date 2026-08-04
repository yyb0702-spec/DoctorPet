package com.doctorpet.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Level 3 — 실제 Redis로 검증(docs/testing/verification-guide.md, SA 부록 B). 리뷰 지적 대응 —
 * 비밀번호 재설정 토큰을 여러 번 발급해도 회원당 활성 토큰이 하나만 유지되는지는 Lua 스크립트의
 * 실제 원자적 동작이라 Mockito 슬라이스로는 검증할 수 없다.
 *
 * 원문이 아니라 해시가 키로 쓰이는지(#123)도 여기서 함께 검증한다 — 실제 Redis 키스페이스에
 * 원문 토큰이 그대로 노출되지 않는지는 저장소 자체의 계약이라 Mockito 슬라이스로는 확인할 수 없다.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost:8080/api/auth/verify-email",
        "mail.password-reset.base-url=http://localhost:3000/reset-password"
})
class MemberTokenRepositoryIntegrationTest {

    private static final Long MEMBER_ID = 1L;
    private static final Duration TTL = Duration.ofHours(1);

    @Autowired
    private MemberTokenRepository memberTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete("pwd-reset-active:" + MEMBER_ID);
    }

    @Test
    void 같은_회원의_비밀번호_재설정_토큰을_두_번_발급하면_이전_토큰은_무효화된다() {
        String firstToken = memberTokenRepository.issuePasswordResetToken(MEMBER_ID, TTL);
        String secondToken = memberTokenRepository.issuePasswordResetToken(MEMBER_ID, TTL);

        assertThat(memberTokenRepository.consumePasswordResetToken(firstToken)).isEmpty();
        assertThat(memberTokenRepository.consumePasswordResetToken(secondToken)).contains(MEMBER_ID);
    }

    @Test
    void 발급된_토큰을_소비하면_활성_포인터도_함께_지워져_재소비가_불가능하다() {
        String token = memberTokenRepository.issuePasswordResetToken(MEMBER_ID, TTL);

        assertThat(memberTokenRepository.consumePasswordResetToken(token)).contains(MEMBER_ID);
        assertThat(memberTokenRepository.consumePasswordResetToken(token)).isEmpty();
        assertThat(redisTemplate.hasKey("pwd-reset-active:" + MEMBER_ID)).isFalse();
    }

    @Test
    void 재설정_토큰_원문은_Redis_키스페이스_어디에도_그대로_저장되지_않는다() {
        String token = memberTokenRepository.issuePasswordResetToken(MEMBER_ID, TTL);

        assertThat(redisTemplate.hasKey("pwd-reset:" + token)).isFalse();
        assertThat(redisTemplate.opsForValue().get("pwd-reset-active:" + MEMBER_ID)).isNotEqualTo(token);

        memberTokenRepository.consumePasswordResetToken(token);
    }
}
