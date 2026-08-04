package com.doctorpet.domain.member.repository;

import com.doctorpet.global.security.TokenHasher;
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
 * 키는 {@code email-verify:{hash(token)}} / {@code pwd-reset:{hash(token)}} 형태로, 값은
 * 회원 ID다(Refresh Token과 반대 방향 — 재발급은 memberId로 토큰을 찾지만, 이메일로 온 링크는
 * 토큰으로 회원을 찾아야 하기 때문). 원문이 아니라 해시를 키로 쓰는 이유(백로그 #123): 이 토큰은
 * 그 자체로 "이메일 인증 완료"·"비밀번호 재설정" 권한을 행사할 수 있는 자격증명이라, Redis가
 * 노출되는 사고가 나면 KEYS/SCAN 등으로 원문(=키 접미사)을 그대로 읽어 인증 메일 수신 없이도
 * 계정을 탈취할 수 있다. 해시를 키로 쓰면 그 사고가 나도 원문을 복원할 방법이 없다
 * (TokenHasher 참고). consume()은 조회와 삭제를 하나의 원자 연산(Lua)으로 묶어 같은 토큰이
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
    private static final String PASSWORD_RESET_ACTIVE_KEY_PREFIX = "pwd-reset-active:";

    private static final RedisScript<String> GET_AND_DELETE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then redis.call('del', KEYS[1]) end "
                    + "return v",
            String.class
    );

    /*
      비밀번호 재설정 토큰 발급 — 회원당 활성 토큰을 하나로만 유지한다(리뷰 지적: 재설정을 여러 번
      요청하면 이전 토큰들도 각자 TTL 만료 전까지 그대로 유효해, 최신 링크로 이미 재설정한 뒤에도
      과거 이메일에 남아있는 옛 링크가 계속 작동하는 문제가 있었다). pwd-reset-active:{memberId}
      키로 "현재 유효한 토큰의 해시"를 별도로 추적해, 새 토큰을 발급할 때 이전 활성 토큰이 있으면
      그 토큰 키(pwd-reset:{oldHash})를 함께 지운다. 두 키 조작을 하나의 Lua 스크립트로 묶어, 이전
      토큰 삭제와 새 토큰 등록 사이에 중간 상태가 노출되지 않게 한다. ARGV[1]은 호출부(Java)에서
      이미 TokenHasher.hash()로 해시해 넘겨주므로, 이 스크립트는 원문을 전혀 다루지 않는다(#123).
     */
    private static final RedisScript<String> ISSUE_SINGLE_ACTIVE_SCRIPT = new DefaultRedisScript<>(
            "local oldHash = redis.call('get', KEYS[1]) "
                    + "if oldHash then redis.call('del', ARGV[3] .. oldHash) end "
                    + "redis.call('set', ARGV[3] .. ARGV[1], ARGV[2], 'PX', ARGV[4]) "
                    + "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[4]) "
                    + "return ARGV[1]",
            String.class
    );

    /*
      비밀번호 재설정 토큰 소비 — 토큰 키(pwd-reset:{hash(token)})뿐 아니라 활성 포인터
      (pwd-reset-active:{memberId})도 함께 지워, 소비 직후에는 "이 회원의 활성 토큰 없음" 상태가
      정확히 반영되게 한다(다음 발급 시 존재하지 않는 옛 포인터를 잘못 지우는 일이 없게).
     */
    private static final RedisScript<String> GET_AND_DELETE_WITH_ACTIVE_CLEANUP_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then "
                    + "redis.call('del', KEYS[1]) "
                    + "redis.call('del', ARGV[1] .. v) "
                    + "end "
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

    /**
     * 이 회원의 이전 활성 재설정 토큰이 있으면 무효화하고, 새 토큰을 유일한 활성 토큰으로 등록한다.
     * 활성 포인터({@code pwd-reset-active:{memberId}})에는 원문이 아니라 해시를 저장한다 —
     * 이전 토큰 키를 지울 때(Lua 내부에서 {@code ARGV[3] .. oldHash})도 그대로 해시 기반 키를
     * 구성하면 되므로, Lua 스크립트 자체는 해시인지 원문인지 몰라도 된다(호출부에서 이미 해시해
     * 넘겨줌).
     */
    public String issuePasswordResetToken(Long memberId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        redisTemplate.execute(
                ISSUE_SINGLE_ACTIVE_SCRIPT,
                List.of(PASSWORD_RESET_ACTIVE_KEY_PREFIX + memberId),
                TokenHasher.hash(token),
                String.valueOf(memberId),
                PASSWORD_RESET_KEY_PREFIX,
                String.valueOf(ttl.toMillis())
        );
        return token;
    }

    /** 토큰이 유효하면 회원 ID를 반환하며 동시에 토큰(및 활성 포인터)을 소비(삭제)한다. 없거나 만료됐으면 빈 값. */
    public Optional<Long> consumePasswordResetToken(String token) {
        String value = redisTemplate.execute(
                GET_AND_DELETE_WITH_ACTIVE_CLEANUP_SCRIPT,
                List.of(PASSWORD_RESET_KEY_PREFIX + TokenHasher.hash(token)),
                PASSWORD_RESET_ACTIVE_KEY_PREFIX
        );
        return Optional.ofNullable(value).map(Long::valueOf);
    }

    private String issue(String keyPrefix, Long memberId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(keyPrefix + TokenHasher.hash(token), String.valueOf(memberId), ttl);
        return token;
    }

    private Optional<Long> consume(String keyPrefix, String token) {
        String value = redisTemplate.execute(GET_AND_DELETE_SCRIPT, List.of(keyPrefix + TokenHasher.hash(token)));
        return Optional.ofNullable(value).map(Long::valueOf);
    }
}
