package com.doctorpet.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;
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
 *
 * tryLock의 펜싱 토큰 발급·단조 증가, rotateIfMatches의 STALE/REUSED 구분(이슈 #100, 재발급 락
 * TTL 레이스)도 여기서 검증한다 — 두 번의 Lua 스크립트 호출이 실제로 원자적으로 상호작용하는지는
 * Mockito 슬라이스로는 확인할 수 없다.
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
        redisTemplate.delete("refresh-lock:" + MEMBER_ID);
        redisTemplate.delete("refresh-fence:" + MEMBER_ID);
        redisTemplate.delete("pwd-changed-at:" + MEMBER_ID);
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
    void 재설정_이력이_없으면_isTokenInvalidatedByPasswordChange가_false다() {
        assertThat(refreshTokenRepository.isTokenInvalidatedByPasswordChange(MEMBER_ID, new Date()))
                .isFalse();
    }

    @Test
    void invalidateTokensIssuedBeforeNow_이후에는_그_이전에_발급된_토큰이_무효로_판정된다() throws InterruptedException {
        Date issuedBeforeReset = new Date();
        Thread.sleep(10);

        refreshTokenRepository.invalidateTokensIssuedBeforeNow(MEMBER_ID, Duration.ofMinutes(1));

        assertThat(refreshTokenRepository.isTokenInvalidatedByPasswordChange(MEMBER_ID, issuedBeforeReset))
                .isTrue();
    }

    @Test
    void invalidateTokensIssuedBeforeNow_이후에_발급된_토큰은_무효로_판정되지_않는다() throws InterruptedException {
        refreshTokenRepository.invalidateTokensIssuedBeforeNow(MEMBER_ID, Duration.ofMinutes(1));
        Thread.sleep(10);
        Date issuedAfterReset = new Date();

        assertThat(refreshTokenRepository.isTokenInvalidatedByPasswordChange(MEMBER_ID, issuedAfterReset))
                .isFalse();
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
    void tryLock은_성공하면_1_이상의_펜싱_토큰을_반환하고_같은_회원에_대한_재시도는_실패한다() {
        Optional<Long> first = refreshTokenRepository.tryLock(MEMBER_ID, "lock-token-a");
        Optional<Long> second = refreshTokenRepository.tryLock(MEMBER_ID, "lock-token-b");

        assertThat(first).isPresent();
        assertThat(first.get()).isGreaterThanOrEqualTo(1L);
        assertThat(second).isEmpty();
    }

    @Test
    void unlock으로_락을_풀면_다음_tryLock은_더_큰_펜싱_토큰을_발급한다() {
        Optional<Long> first = refreshTokenRepository.tryLock(MEMBER_ID, "lock-token-a");
        refreshTokenRepository.unlock(MEMBER_ID, "lock-token-a");

        Optional<Long> second = refreshTokenRepository.tryLock(MEMBER_ID, "lock-token-b");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get()).isGreaterThan(first.get());
    }

    @Test
    void rotateIfMatches는_저장된_해시와_일치하는_원문을_제시했을_때만_회전에_성공한다() {
        String oldToken = "old-refresh-token";
        String newToken = "new-refresh-token";
        refreshTokenRepository.save(MEMBER_ID, oldToken, Duration.ofMinutes(1));

        RotateResult result = refreshTokenRepository.rotateIfMatches(MEMBER_ID, 1L, oldToken, newToken, Duration.ofMinutes(1));

        assertThat(result).isEqualTo(RotateResult.SUCCESS);
        assertThat(refreshTokenRepository.matches(MEMBER_ID, newToken)).isTrue();
        assertThat(refreshTokenRepository.matches(MEMBER_ID, oldToken)).isFalse();
    }

    /*
     * 배포 마이그레이션 회귀 검증(리뷰 지적) — 배포 전 Redis에는 원문 Refresh Token이 저장돼
     * 있었다. save()를 거치지 않고 redisTemplate로 직접 원문을 써서 그 상태를 재현한다.
     */
    @Test
    void rotateIfMatches는_배포_전_원문으로_저장된_토큰도_회전에_성공하고_이후엔_해시로_저장된다() {
        String legacyRawToken = "legacy-raw-refresh-token";
        String newToken = "new-refresh-token-after-migration";
        redisTemplate.opsForValue().set("refresh:" + MEMBER_ID, legacyRawToken, Duration.ofMinutes(1));

        RotateResult result = refreshTokenRepository.rotateIfMatches(MEMBER_ID, 1L, legacyRawToken, newToken, Duration.ofMinutes(1));

        assertThat(result).isEqualTo(RotateResult.SUCCESS);
        String stored = redisTemplate.opsForValue().get("refresh:" + MEMBER_ID);
        assertThat(stored).isNotEqualTo(newToken); // 새 값은 원문이 아니라 해시로(펜싱 토큰 접두사와 함께) 저장된다
        assertThat(refreshTokenRepository.matches(MEMBER_ID, newToken)).isTrue();
    }

    @Test
    void rotateIfMatches는_원문도_해시도_일치하지_않으면_재사용으로_판정하고_세션을_삭제한다() {
        redisTemplate.opsForValue().set("refresh:" + MEMBER_ID, "legacy-raw-refresh-token", Duration.ofMinutes(1));

        RotateResult result = refreshTokenRepository.rotateIfMatches(
                MEMBER_ID, 1L, "completely-different-token", "new-refresh-token", Duration.ofMinutes(1)
        );

        assertThat(result).isEqualTo(RotateResult.REUSED);
        assertThat(redisTemplate.hasKey("refresh:" + MEMBER_ID)).isFalse();
    }

    /*
     * 이슈 #100(재발급 락 TTL 레이스) 회귀 검증 — 락 TTL이 만료돼 더 최신 요청이 새 락(더 큰
     * 펜싱 토큰)을 얻어 먼저 회전에 성공한 뒤, 원래 요청이 뒤늦게 도착시키는 회전 시도는 STALE로
     * 판정돼야 하고, 그 시도가 방금 성공한 회전의 세션을 지워서는 안 된다.
     */
    @Test
    void rotateIfMatches는_더_최신_펜싱_토큰이_이미_회전에_성공했으면_세션을_건드리지_않고_STALE을_반환한다() {
        String presentedToken = "shared-old-refresh-token";
        String winnerNewToken = "winner-new-refresh-token";
        String staleNewToken = "stale-new-refresh-token";
        refreshTokenRepository.save(MEMBER_ID, presentedToken, Duration.ofMinutes(1));

        // 더 최신(큰) 펜싱 토큰(2)을 가진 요청이 먼저 회전에 성공한다.
        RotateResult winnerResult =
                refreshTokenRepository.rotateIfMatches(MEMBER_ID, 2L, presentedToken, winnerNewToken, Duration.ofMinutes(1));
        assertThat(winnerResult).isEqualTo(RotateResult.SUCCESS);

        // 락 TTL 만료로 뒤늦게 도착한, 더 오래된(작은) 펜싱 토큰(1)의 시도는 STALE이어야 한다.
        RotateResult staleResult =
                refreshTokenRepository.rotateIfMatches(MEMBER_ID, 1L, presentedToken, staleNewToken, Duration.ofMinutes(1));
        assertThat(staleResult).isEqualTo(RotateResult.STALE);

        // 핵심 회귀 검증: 승자의 새 세션이 그대로 살아있어야 한다.
        assertThat(refreshTokenRepository.matches(MEMBER_ID, winnerNewToken)).isTrue();
        assertThat(refreshTokenRepository.matches(MEMBER_ID, staleNewToken)).isFalse();
    }

    /*
     * 2차 리뷰 지적 회귀 검증 — 더 최신 락(tryLock)이 이미 발급됐지만 그 소유자가 아직
     * rotateIfMatches를 실행하지 못한 사이, 락 TTL 만료로 뒤늦게 살아난 예전 락 소유자가 먼저
     * rotateIfMatches를 시도하는 순서다. 이때 refresh:{memberId}에는 아직 아무도 회전을 성공시키지
     * 않아 펜싱 토큰이 기록돼 있지 않으므로(콜론 없음 → storedFence=0), 저장된 값만 보면 예전
     * 요청의 펜싱 토큰이 더 커 보여 통과해버린다. refresh-fence 카운터의 현재 값까지 함께 봐야
     * 이 시점에도 정확히 걸러낼 수 있다.
     */
    @Test
    void rotateIfMatches는_더_최신_락이_발급됐지만_아직_회전하지_않은_예전_락_소유자의_시도를_STALE로_거부한다() {
        String presentedToken = "shared-old-refresh-token";
        String staleAttemptToken = "stale-attempt-new-token";
        refreshTokenRepository.save(MEMBER_ID, presentedToken, Duration.ofMinutes(1));

        // A가 락을 얻는다(펜싱 토큰 1) — 이후 처리 지연으로 락 TTL이 만료됐다고 가정하고,
        // 실제로 기다리는 대신 락 키만 지워 TTL 만료를 재현한다.
        Optional<Long> fenceA = refreshTokenRepository.tryLock(MEMBER_ID, "lock-token-a");
        redisTemplate.delete("refresh-lock:" + MEMBER_ID);

        // B가 락을 새로 얻는다(펜싱 토큰 2, refresh-fence 카운터도 2로 증가) — 아직 회전은
        // 실행하지 않은 상태다.
        Optional<Long> fenceB = refreshTokenRepository.tryLock(MEMBER_ID, "lock-token-b");
        assertThat(fenceA).isPresent();
        assertThat(fenceB).isPresent();
        assertThat(fenceB.get()).isGreaterThan(fenceA.get());

        // B가 회전을 실행하기 전에, 뒤늦게 살아난 A가 먼저 rotateIfMatches를 시도한다.
        RotateResult staleAttemptResult = refreshTokenRepository.rotateIfMatches(
                MEMBER_ID, fenceA.get(), presentedToken, staleAttemptToken, Duration.ofMinutes(1));

        assertThat(staleAttemptResult).isEqualTo(RotateResult.STALE);
        // 핵심 회귀 검증: A의 시도가 세션을 건드리지 않아, 원래 토큰이 여전히 유효해야 한다.
        assertThat(refreshTokenRepository.matches(MEMBER_ID, presentedToken)).isTrue();
        assertThat(refreshTokenRepository.matches(MEMBER_ID, staleAttemptToken)).isFalse();

        // B는 정상적으로 회전에 성공할 수 있어야 한다.
        String winnerNewToken = "winner-new-token";
        RotateResult winnerResult = refreshTokenRepository.rotateIfMatches(
                MEMBER_ID, fenceB.get(), presentedToken, winnerNewToken, Duration.ofMinutes(1));
        assertThat(winnerResult).isEqualTo(RotateResult.SUCCESS);
        assertThat(refreshTokenRepository.matches(MEMBER_ID, winnerNewToken)).isTrue();
    }
}
