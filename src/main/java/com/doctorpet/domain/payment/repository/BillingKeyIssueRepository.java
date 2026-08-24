package com.doctorpet.domain.payment.repository;

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
 * PortOne 모바일 콜백을 인증 회원에게 귀속하는 단기·1회성 발급 시도 저장소다.
 *
 * <p>URL에는 memberId나 빌링키가 아닌 난수 issueId만 들어간다. 콜백 소비는 GET+DEL 원자 연산으로
 * 처리해 같은 발급 결과를 동시에 여러 번 등록하지 못하게 한다.
 */
@Repository
@RequiredArgsConstructor
public class BillingKeyIssueRepository {

    private static final String KEY_PREFIX = "payment-method-issue:";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final RedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then redis.call('del', KEYS[1]) end "
                    + "return v",
            String.class
    );

    private final StringRedisTemplate redisTemplate;

    public String issue(Long memberId) {
        String issueId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key(issueId), String.valueOf(memberId), TTL);
        return issueId;
    }

    public Optional<Long> consume(String issueId) {
        String memberId = redisTemplate.execute(CONSUME_SCRIPT, List.of(key(issueId)));
        return memberId == null ? Optional.empty() : Optional.of(Long.valueOf(memberId));
    }

    /** 사용자가 PortOne 창을 취소한 경우에도 같은 발급 시도를 재사용하지 못하게 폐기한다. */
    public void discard(String issueId) {
        redisTemplate.delete(key(issueId));
    }

    private String key(String issueId) {
        return KEY_PREFIX + issueId;
    }
}
