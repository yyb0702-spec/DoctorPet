package com.doctorpet.domain.payment.scheduler;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/*
  결제 정산 배치의 다중 인스턴스 실행 락(#35). 여러 서버 인스턴스가 동시에 배치를 돌리지 않도록 Redis SET NX PX로
  단일 실행을 보장한다(member 도메인이 쓰는 것과 같은 원시). TTL은 배치 최대 실행시간보다 길고 실행 주기보다 짧게 둬,
  크래시로 unlock을 못 불러도 다음 주기 전에 자동 해제되게 한다. unlock은 자신이 잡은 락(token 일치)만 해제한다.
 */
@Component
@RequiredArgsConstructor
public class ReconcileLock {

    private static final String LOCK_KEY = "lock:payment:reconcile";
    // 배치가 아무리 길어도 이 시간 안에 끝난다는 전제. 실행 주기(5분)보다 짧게 둬 크래시 시 다음 주기에 회복한다.
    private static final Duration LOCK_TTL = Duration.ofMinutes(4);

    private final StringRedisTemplate redisTemplate;

    /** 락 획득을 시도한다. 성공하면 해제에 쓸 토큰을 반환하고, 이미 다른 인스턴스가 쥐고 있으면 빈 값을 반환한다. */
    public Optional<String> tryLock() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    /** 내가 잡은 락(token 일치)일 때만 해제한다 — 뒤늦은 해제가 다른 인스턴스의 락을 지우지 않게 한다. */
    public void unlock(String token) {
        String current = redisTemplate.opsForValue().get(LOCK_KEY);
        if (token.equals(current)) {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}
