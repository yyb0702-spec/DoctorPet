package com.doctorpet.domain.member.repository;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token을 Redis에 저장한다. 키는 회원당 {@code refresh:{memberId}} 단일 키다(SA §6-1) —
 * 즉 회원당 세션이 하나뿐이며, 저장(덮어쓰기) 자체가 곧 회전(rotate)이다: 같은 키에 새 값을 쓰면
 * 이전 토큰은 더 이상 저장된 값과 일치하지 않으므로 자동으로 무효화된다([[A 도메인]] #6).
 * 로그아웃 시의 삭제는 별도 Issue에서 다룬다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    public void save(Long memberId, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(memberId), refreshToken, ttl);
    }

    /** 재발급 시 화이트리스트 대조용. 저장된 값이 없으면(만료·로그아웃 등) 빈 값을 반환한다. */
    public Optional<String> findByMemberId(Long memberId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(memberId)));
    }

    /** 재사용 감지 시 세션을 완전히 무효화하기 위해 호출한다. */
    public void deleteByMemberId(Long memberId) {
        redisTemplate.delete(key(memberId));
    }

    private String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }
}
