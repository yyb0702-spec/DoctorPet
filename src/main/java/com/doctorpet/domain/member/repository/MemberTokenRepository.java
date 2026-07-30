package com.doctorpet.domain.member.repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 이메일 인증·비밀번호 재설정 토큰을 Redis에 저장한다(백로그 P2). RefreshTokenRepository와 같은
 * 이유로 Redis를 쓴다 — TTL이 지나면 Redis가 알아서 키를 지워주므로 별도의 만료 토큰 정리
 * 배치(@Scheduled)가 필요 없다.
 *
 * 키는 {@code email-verify:{token}} / {@code pwd-reset:{token}} 형태로, 값은 회원 ID다
 * (Refresh Token과 반대 방향 — 재발급은 memberId로 토큰을 찾지만, 이메일로 온 링크는 토큰으로
 * 회원을 찾아야 하기 때문). consume()은 조회와 삭제를 하나의 원자 연산(Lua)으로 묶어 같은 토큰이
 * 동시에 두 번 요청되어도 정확히 한쪽만 성공하게 한다(1회용 보장) — 삭제된 뒤(또는 애초에 없거나
 * TTL 만료로 사라진) 토큰은 "유효하지 않음"과 "만료됨"을 구분할 수 없는데, Redis 특성상 이 둘을
 * 구분할 방법이 없으므로(만료되면 키 자체가 사라짐) 호출부는 항상 하나의 에러 코드
 * (MemberErrorCode#INVALID_OR_EXPIRED_TOKEN)로만 응답한다.
 */
@Repository
@RequiredArgsConstructor
public class MemberTokenRepository {

    private static final String EMAIL_VERIFICATION_KEY_PREFIX = "email-verify:";
    private static final String PASSWORD_RESET_KEY_PREFIX = "pwd-reset:";

    private static final RedisScript<String> GET_AND_DELETE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then redis.call('del', KEYS[1]) end "
                    + "return v",
            String.class
    );

    private final StringRedisTemplate redisTemplate;

    public String issueEmailVerificationToken(Long memberId, Duration ttl) {
        return issue(EMAIL_VERIFICATION_KEY_PREFIX, memberId, ttl);
    }

    /** 토큰이 유효하면 회원 ID를 반환하며 동시에 토큰을 소비(삭제)한다. 없거나 만료됐으면 빈 값. */
    public Optional<Long> consumeEmailVerificationToken(String token) {
        return consume(EMAIL_VERIFICATION_KEY_PREFIX, token);
    }

    public String issuePasswordResetToken(Long memberId, Duration ttl) {
        return issue(PASSWORD_RESET_KEY_PREFIX, memberId, ttl);
    }

    /** 토큰이 유효하면 회원 ID를 반환하며 동시에 토큰을 소비(삭제)한다. 없거나 만료됐으면 빈 값. */
    public Optional<Long> consumePasswordResetToken(String token) {
        return consume(PASSWORD_RESET_KEY_PREFIX, token);
    }

    private String issue(String keyPrefix, Long memberId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(keyPrefix + token, String.valueOf(memberId), ttl);
        return token;
    }

    private Optional<Long> consume(String keyPrefix, String token) {
        String value = redisTemplate.execute(GET_AND_DELETE_SCRIPT, List.of(keyPrefix + token));
        return Optional.ofNullable(value).map(Long::valueOf);
    }
}
