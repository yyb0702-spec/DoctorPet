package com.doctorpet.domain.ai.repository;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AiRateLimitRepository {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL =
            new DefaultRedisScript<>(
                    "local count = redis.call('INCR', KEYS[1]); "
                            + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                            + "return count;",
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    public long increment(String key, Duration ttl) {
        Long count = redisTemplate.execute(
                INCREMENT_WITH_TTL,
                List.of(key),
                String.valueOf(ttl.toMillis())
        );
        if (count == null) {
            throw new IllegalStateException("AI 상담 Rate Limit 카운터 결과가 없습니다.");
        }
        return count;
    }
}
