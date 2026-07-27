package com.doctorpet.domain.member.repository;

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
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refresh:";

    /*
     * 현재 저장된 값이 oldToken과 일치할 때만 newToken으로 교체한다(compare-and-set).
     * "조회 후 저장"을 두 단계로 나누면 같은 Refresh Token으로 동시에 들어온 재발급 요청이
     * 둘 다 비교를 통과해 각자 새 토큰을 저장하는 경쟁 상태가 생긴다 — Redis에게 GET과 SET을
     * 하나의 원자 연산(Lua)으로 실행시켜, 동시 요청 중 정확히 하나만 성공하도록 만든다.
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

    private final StringRedisTemplate redisTemplate;

    public void save(Long memberId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(memberId), refreshToken, ttl);
    }

    /** 재발급 시 화이트리스트 대조용. 저장된 값이 없으면(만료·로그아웃 등) 빈 값을 반환한다. */
    public Optional<String> findByMemberId(Long memberId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(memberId)));
    }

    /**
     * 현재 저장된 값이 {@code oldToken}과 정확히 일치할 때만 {@code newToken}으로 원자적으로 교체한다.
     * 일치하지 않으면(이미 회전되어 폐기된 토큰의 재사용, 또는 저장된 값이 아예 없는 경우) false를 반환한다.
     */
    public boolean rotateIfMatches(Long memberId, String oldToken, String newToken, Duration ttl) {
        Long result = redisTemplate.execute(
                ROTATE_IF_MATCHES_SCRIPT,
                List.of(key(memberId)),
                oldToken, newToken, String.valueOf(ttl.toMillis())
        );
        return result != null && result == 1L;
    }

    /** 재사용 감지 시, 또는 로그아웃 시 세션을 완전히 무효화하기 위해 호출한다. */
    public void deleteByMemberId(Long memberId) {
        redisTemplate.delete(key(memberId));
    }

    private String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }
}
