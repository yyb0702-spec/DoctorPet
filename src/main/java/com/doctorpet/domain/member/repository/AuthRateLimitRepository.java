package com.doctorpet.domain.member.repository;

import com.doctorpet.domain.member.exception.AuthRateLimitStorageException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * 인증메일 관련 엔드포인트(회원가입, 이메일 인증 재발송, 비밀번호 재설정 요청) rate limit
 * 카운터(기능 구멍 점검 대응) — domain.ai.repository.AiRateLimitRepository와 동일한 원자적
 * INCR+PEXPIRE 패턴이다(도메인 경계상 그 클래스를 직접 참조할 수 없어 별도로 둔다).
 *
 * 저장소 오류(연결 실패 등)와 결과 없음(null)을 모두 AuthRateLimitStorageException으로 통일해
 * 던진다 — 호출부(AuthRateLimiter)가 이 타입만 fail-open(허용) 대상으로 잡고, 그 외 예상하지
 * 못한 런타임 예외는 숨기지 않고 그대로 전파한다(AiRateLimitRepository와 동일한 설계).
 */
@Repository
@RequiredArgsConstructor
public class AuthRateLimitRepository {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count;",
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    public long increment(String key, Duration ttl) {
        Long count;
        try {
            count = redisTemplate.execute(
                    INCREMENT_WITH_TTL,
                    List.of(key),
                    String.valueOf(ttl.toMillis())
            );
        } catch (RuntimeException exception) {
            throw new AuthRateLimitStorageException(
                    "인증메일 rate limit 저장소 처리에 실패했습니다.", exception
            );
        }
        if (count == null) {
            throw new AuthRateLimitStorageException("인증메일 rate limit 카운터 결과가 없습니다.");
        }
        return count;
    }
}
