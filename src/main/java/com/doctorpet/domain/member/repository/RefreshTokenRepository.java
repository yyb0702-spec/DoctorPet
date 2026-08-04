package com.doctorpet.domain.member.repository;

import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.TokenHasher;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token을 Redis에 저장한다. 키는 회원당 {@code refresh:{memberId}} 단일 키다(SA §6-1) —
 * 즉 회원당 세션이 하나뿐이며, 저장(덮어쓰기) 자체가 곧 회전(rotate)이다: 같은 키에 새 값을 쓰면
 * 이전 토큰은 더 이상 저장된 값과 일치하지 않으므로 자동으로 무효화된다([[A 도메인]] #6).
 *
 * 값은 토큰 원문이 아니라 SHA-256 해시를 저장한다(백로그 #123). Redis 값 자체가 "현재 유효한
 * 세션"의 증거이자 그대로 재발급에 쓸 수 있는 자격증명이므로, 원문을 저장하면 Redis가 노출되는
 * 사고(백업 유출, 오설정으로 인한 외부 접근, 관리자 콘솔 오남용 등)만으로 서명 검증 없이 곧바로
 * 세션을 탈취할 수 있다. 해시를 저장하면 그 사고가 나도 원문을 복원할 방법이 없다. salt/pepper를
 * 쓰지 않는 이유: 비밀번호와 달리 Refresh Token은 서버가 임의로 생성하는 고엔트로피 값이라
 * 레인보우테이블·사전 대입 공격 표면이 없고, 애초에 오프라인으로 원문을 추측해 시도할 수 있는
 * 통로도 없다(재발급은 항상 JWT 서명 검증을 먼저 통과해야 한다).
 *
 * MemberBlacklistPort·AccessTokenBlacklistPort도 함께 구현한다 — 탈퇴 회원(회원 단위)·로그아웃한
 * 특정 토큰(토큰 단위)을 인증 단계(global.security.JwtAuthenticationFilter)에서 걸러내려면 그
 * 필터가 이 저장소를 참조해야 하는데, global 패키지가 domain을 직접 참조하면 안 되므로 인터페이스는
 * global.security에 두고 여기서 구현만 제공한다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository implements MemberBlacklistPort, AccessTokenBlacklistPort {

    private static final String KEY_PREFIX = "refresh:";
    private static final String LOCK_KEY_PREFIX = "refresh-lock:";
    private static final String WITHDRAWN_KEY_PREFIX = "withdrawn:";
    private static final String LOGOUT_BLACKLIST_KEY_PREFIX = "at-blacklist:";

    // 회원당 재발급 요청을 직렬화하는 락의 TTL. 크래시 등으로 unlock()이 못 불려도 이 시간 뒤엔
    // 자동으로 풀린다 — 정상 처리(회원 조회 + JWT 2개 생성 + Redis CAS 1회)는 이보다 훨씬 빨리 끝난다.
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    /*
     * 현재 저장된 값이 oldToken과 일치할 때만 newToken으로 교체한다(compare-and-set).
     * "조회 후 저장"을 두 단계로 나누면 같은 Refresh Token으로 동시에 들어온 재발급 요청이
     * 둘 다 비교를 통과해 각자 새 토큰을 저장하는 경쟁 상태가 생긴다 — Redis에게 GET과 SET을
     * 하나의 원자 연산(Lua)으로 실행시켜, 동시 요청 중 정확히 하나만 성공하도록 만든다.
     *
     * (이전에는 CAS 실패 시 "동시 중복 요청"과 "진짜 재사용"을 5초 유예 창으로 구분했는데, 그
     * 창 안에서는 실제 탈취 토큰 재사용도 눈감아주는 셈이라 리뷰에서 보안 약화로 지적됐다.
     * 지금은 AuthService.reissue()가 CAS를 시도하기 전에 tryLock/unlock으로 회원당 재발급을
     * 아예 직렬화한다 — 그러면 두 요청이 "동시에" CAS를 다투는 상황 자체가 생기지 않으므로,
     * CAS 실패는 항상 "이미 다른 요청이 정상 처리한 뒤의 진짜 재사용"으로 취급해도 안전하다.)
     */
    private static final RedisScript<Long> ROTATE_IF_MATCHES_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) "
                    + "return 1 "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

    // 락을 쥔 요청만 스스로 풀 수 있게 한다(get-then-del을 원자적으로) — 그렇지 않으면 TTL 만료 후
    // 다른 요청이 새로 잡은 락을, 뒤늦게 unlock()을 호출한 원래 요청이 실수로 풀어버릴 수 있다.
    private static final RedisScript<Long> RELEASE_LOCK_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('del', KEYS[1]) "
                    + "return 1 "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 토큰 원문이 아니라 그 해시를 저장한다(#123 — 클래스 Javadoc 참고). */
    public void save(Long memberId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(memberId), TokenHasher.hash(refreshToken), ttl);
    }

    /**
     * 저장된 값(해시)을 그대로 반환한다 — 토큰 원문을 저장하지 않으므로 이 값과 원문을 직접
     * 비교할 수는 없다. 원문 토큰 하나가 유효한지 확인하려면 {@link #matches(Long, String)}를 쓴다.
     */
    public Optional<String> findByMemberId(Long memberId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(memberId)));
    }

    /** 제시된 원문 토큰을 해시해, 현재 저장된 값과 일치하는지 확인한다. */
    public boolean matches(Long memberId, String rawToken) {
        return findByMemberId(memberId)
                .map(storedHash -> storedHash.equals(TokenHasher.hash(rawToken)))
                .orElse(false);
    }

    /**
     * 현재 저장된 값(해시)이 {@code oldToken}의 해시와 정확히 일치할 때만 {@code newToken}의
     * 해시로 원자적으로 교체한다. 일치하지 않으면(이미 회전되어 폐기된 토큰의 재사용, 또는 저장된
     * 값이 아예 없는 경우) false를 반환한다.
     */
    public boolean rotateIfMatches(Long memberId, String oldToken, String newToken, Duration ttl) {
        Long result = redisTemplate.execute(
                ROTATE_IF_MATCHES_SCRIPT,
                List.of(key(memberId)),
                TokenHasher.hash(oldToken), TokenHasher.hash(newToken), String.valueOf(ttl.toMillis())
        );
        return result != null && result == 1L;
    }

    /** 재사용 감지 시, 또는 로그아웃 시 세션을 완전히 무효화하기 위해 호출한다. */
    public void deleteByMemberId(Long memberId) {
        redisTemplate.delete(key(memberId));
    }

    /**
     * 회원당 재발급 요청을 직렬화하는 락을 시도한다(SET NX PX). 성공하면 그 회원에 대한 다른
     * 재발급 요청은 이 락이 풀릴 때까지(명시적 unlock 또는 TTL 만료) 즉시 실패해야 한다 — 그래야
     * 같은 토큰으로 동시에 들어온 요청들이 서로 CAS를 다투는 상황 자체가 생기지 않는다.
     * {@code lockToken}은 호출자를 식별하는 임의의 값(예: UUID)으로, unlock 시 본인 락인지
     * 확인하는 데 쓴다.
     */
    public boolean tryLock(Long memberId, String lockToken) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey(memberId), lockToken, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /** tryLock으로 얻은 락을 해제한다. lockToken이 일치할 때만(즉 내가 쥔 락일 때만) 지운다. */
    public void unlock(Long memberId, String lockToken) {
        redisTemplate.execute(RELEASE_LOCK_IF_OWNED_SCRIPT, List.of(lockKey(memberId)), lockToken);
    }

    /*
     * 탈퇴 회원 Access Token 블랙리스트(리뷰 지적 P1 대응) — JWT는 무상태라 탈퇴 시점에 이미
     * 발급된 Access Token을 서버가 즉시 폐기할 수단이 없다. Refresh Token 삭제(재발급 차단)만으로는
     * 만료 전까지 남은 Access Token으로 다른 도메인의 쓰기 API를 호출하는 것까지는 막지 못한다.
     *
     * TTL을 Access Token 만료 시간과 정확히 맞춰서(JwtProperties#getAccessTokenExpiration()),
     * 그 시점 이후엔 어차피 자연 만료라 블랙리스트 항목도 자동으로 사라지게 한다 — 별도의 정리
     * 배치가 필요 없고, 항목이 무한히 쌓이지도 않는다. JwtAuthenticationFilter가 매 요청마다
     * MySQL 조회 대신 이 키의 존재만 빠르게 확인한다(EXISTS, O(1)).
     */
    public void blacklistMember(Long memberId, Duration ttl) {
        redisTemplate.opsForValue().set(withdrawnKey(memberId), "1", ttl);
    }

    /** JwtAuthenticationFilter가 Access Token 인증 직전에 호출해, 탈퇴한 회원인지 확인한다. */
    @Override
    public boolean isBlacklisted(Long memberId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(withdrawnKey(memberId)));
    }

    /*
     * 로그아웃 시 Access Token 블랙리스트(#124) — JWT는 무상태라 로그아웃 시점에 이미 발급된
     * Access Token을 서버가 즉시 폐기할 수단이 없다. Refresh Token 삭제(재발급 차단)만으로는
     * 만료 전까지 남은 Access Token으로 API를 계속 호출하는 것까지는 막지 못한다.
     *
     * 탈퇴(blacklistMember)와 달리 회원 단위가 아니라 jti(토큰 고유 ID) 단위로 막는다 — 단일
     * 세션 정책상 회원 단위로 막으면, 로그아웃 직후 재로그인해서 받은 새 Access Token까지
     * 같은 memberId라는 이유로 함께 막혀버린다. jti는 토큰마다 유일하므로 "로그아웃한 바로 그
     * 토큰"만 정확히 걸러낸다.
     *
     * TTL은 호출자(AuthService)가 그 토큰의 실제 남은 수명(JwtTokenProvider#getRemainingTtl)을
     * 넘겨준다 — 그 시점 이후엔 어차피 자연 만료라 블랙리스트 항목도 자동으로 사라진다. ttl이
     * 0 이하(경쟁 상태로 이미 만료된 토큰)면 Redis에 쓰지 않는다 — 어차피 서명 검증에서 걸러지고,
     * PX에 0/음수를 넘기면 Redis가 에러를 낸다.
     */
    public void blacklistAccessToken(String jti, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(logoutBlacklistKey(jti), "1", ttl);
    }

    /** JwtAuthenticationFilter가 Access Token 인증 직전에 호출해, 로그아웃된 토큰인지 확인한다. */
    @Override
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(logoutBlacklistKey(jti)));
    }

    private String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }

    private String lockKey(Long memberId) {
        return LOCK_KEY_PREFIX + memberId;
    }

    private String withdrawnKey(Long memberId) {
        return WITHDRAWN_KEY_PREFIX + memberId;
    }

    private String logoutBlacklistKey(String jti) {
        return LOGOUT_BLACKLIST_KEY_PREFIX + jti;
    }
}
