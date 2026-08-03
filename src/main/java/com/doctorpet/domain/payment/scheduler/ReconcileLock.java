package com.doctorpet.domain.payment.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/*
  결제 정산 배치의 다중 인스턴스 실행 락(#35). 여러 서버 인스턴스가 동시에 배치를 돌리지 않도록 Redis SET NX PX로
  단일 실행을 보장한다(member 도메인이 쓰는 것과 같은 원시). TTL은 배치 "한 건"(외부 단건조회 1회)이 넘지 않는다는
  전제의 임대(lease)다 — 배치 전체가 길어져도 처리 중 renew로 임대를 갱신하므로, 만료로 다른 인스턴스가 끼어들지
  않는다. 실행 주기(5분)보다 짧게 둬 크래시로 unlock을 못 불러도 다음 주기 전에 자동 해제된다. renew·unlock 모두
  내 토큰일 때만(get-then-op을 원자적으로) 동작해, 뒤늦은 호출이 다른 인스턴스의 락을 건드리지 못하게 한다.
 */
@Component
@RequiredArgsConstructor
public class ReconcileLock {

    private static final String LOCK_KEY = "lock:payment:reconcile";
    // 배치 "한 건"이 아무리 길어도 이 시간 안에 끝난다는 전제. 처리 중 renew로 이 값만큼 임대를 재연장한다.
    private static final Duration LOCK_TTL = Duration.ofMinutes(4);

    // 내가 쥔 락(token 일치)일 때만 임대를 재연장한다(get-then-pexpire를 원자적으로). 배치가 길어져도 처리 중
    // 만료로 다른 인스턴스가 같은 대상에 락을 얻어 finalizeOutcome을 동시에 실행하는 것을 막는다.
    private static final RedisScript<Long> RENEW_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]) "
                    + "return 1 "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

    // 락을 쥔 인스턴스만 스스로 풀 수 있게 한다(get-then-del을 원자적으로) — 그렇지 않으면 TTL 만료 후 다른
    // 인스턴스가 새로 잡은 락을, 뒤늦게 unlock을 호출한 원래 인스턴스가 실수로 풀어버릴 수 있다(member 도메인과 동일 원시).
    private static final RedisScript<Long> RELEASE_IF_OWNED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('del', KEYS[1]) "
                    + "return 1 "
                    + "else "
                    + "return 0 "
                    + "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 락 획득을 시도한다. 성공하면 해제·갱신에 쓸 토큰을 반환하고, 이미 다른 인스턴스가 쥐고 있으면 빈 값을 반환한다. */
    public Optional<String> tryLock() {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    /** 내가 쥔 락(token 일치)이면 임대를 LOCK_TTL만큼 재연장한다. 처리 중 만료를 막아 단일 실행을 유지한다. */
    public void renew(String token) {
        redisTemplate.execute(
                RENEW_IF_OWNED_SCRIPT, List.of(LOCK_KEY), token, String.valueOf(LOCK_TTL.toMillis()));
    }

    /** 내가 잡은 락(token 일치)일 때만 원자적으로 해제한다 — 뒤늦은 해제가 다른 인스턴스의 락을 지우지 않게 한다. */
    public void unlock(String token) {
        redisTemplate.execute(RELEASE_IF_OWNED_SCRIPT, List.of(LOCK_KEY), token);
    }
}
