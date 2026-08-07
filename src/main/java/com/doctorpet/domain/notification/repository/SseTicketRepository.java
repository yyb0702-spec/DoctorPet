package com.doctorpet.domain.notification.repository;

/*
  SSE 구독 티켓을 Redis에 저장한다(SA §9-8). EventSource는 커스텀 헤더(JWT)를 실을 수 없으므로,
  인증된 요청으로 단기(30초)·1회성 티켓을 발급받아 구독 시 쿼리로 제시한다. 티켓 값 자체는 memberId를
  담지 않는 무의미한 난수(UUID)이며, Redis에만 memberId 매핑을 둔다 — 티켓이 URL·로그에 남아도
  그 자체로는 식별 정보가 아니고, 소비 즉시 삭제되어 재사용할 수 없다.
 */

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SseTicketRepository {

    private static final String KEY_PREFIX = "sse-ticket:";
    // 발급 직후 구독에만 쓰는 값이라 짧게 잡는다. 만료·미사용 티켓은 TTL로 자동 소멸한다.
    private static final Duration TTL = Duration.ofSeconds(30);

    // GET과 DEL을 원자적으로 실행해 티켓을 1회성으로 만든다 — 동시에 두 번 제시돼도 한쪽만 memberId를 얻는다.
    private static final RedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then redis.call('del', KEYS[1]) end "
                    + "return v",
            String.class
    );

    private final StringRedisTemplate redisTemplate;

    // 인증된 회원에게 1회성 구독 티켓을 발급한다.
    public String issue(Long memberId) {
        String ticket = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(key(ticket), String.valueOf(memberId), TTL);
        return ticket;
    }

    // 티켓을 소비하고 매핑된 memberId를 반환한다. 없거나 만료·이미 사용됐으면 empty.
    public Optional<Long> consume(String ticket) {
        String memberId = redisTemplate.execute(CONSUME_SCRIPT, List.of(key(ticket)));
        return memberId == null ? Optional.empty() : Optional.of(Long.valueOf(memberId));
    }

    private String key(String ticket) {
        return KEY_PREFIX + ticket;
    }
}
