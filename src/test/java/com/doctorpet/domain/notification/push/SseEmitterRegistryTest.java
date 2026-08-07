package com.doctorpet.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEmitterRegistryTest {

    private static final Long MEMBER_A = 1L;
    private static final Long MEMBER_B = 2L;
    private static final int CAP = 5;

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    @DisplayName("등록하면 해당 수신자의 연결 수가 늘고, 수신자별로 격리된다")
    void register_countsPerMember() {
        registry.tryRegister(MEMBER_A, CAP);
        registry.tryRegister(MEMBER_A, CAP);
        registry.tryRegister(MEMBER_B, CAP);

        assertThat(registry.connectionCount(MEMBER_A)).isEqualTo(2);
        assertThat(registry.connectionCount(MEMBER_B)).isEqualTo(1);
        assertThat(registry.connectionCount(999L)).isZero();
    }

    @Test
    @DisplayName("상한에 도달하면 null을 반환해 등록을 거절하고, 다른 수신자는 영향받지 않는다")
    void tryRegister_atCap_returnsNull() {
        for (int i = 0; i < CAP; i++) {
            assertThat(registry.tryRegister(MEMBER_A, CAP)).isNotNull();
        }

        assertThat(registry.tryRegister(MEMBER_A, CAP)).isNull();
        assertThat(registry.connectionCount(MEMBER_A)).isEqualTo(CAP);
        assertThat(registry.tryRegister(MEMBER_B, CAP)).isNotNull();
    }

    @Test
    @DisplayName("동시 구독이 몰려도 상한을 초과해 등록되지 않는다(검사와 등록이 원자적)")
    void tryRegister_concurrent_neverExceedsCap() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SseEmitter>> results = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return registry.tryRegister(MEMBER_A, CAP);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int registered = 0;
            for (Future<SseEmitter> result : results) {
                if (result.get(5, TimeUnit.SECONDS) != null) {
                    registered++;
                }
            }

            // 상한을 통과한 요청만 등록되고, 나머지는 모두 거절돼야 한다.
            assertThat(registered).isEqualTo(CAP);
            assertThat(registry.connectionCount(MEMBER_A)).isEqualTo(CAP);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("마지막 연결을 제거하면 키가 정리되고, 이후 새로 등록할 수 있다")
    void remove_lastConnection_clearsKeyAndAllowsReRegister() {
        SseEmitter first = registry.tryRegister(MEMBER_A, CAP);
        assertThat(first).isNotNull();

        registry.remove(MEMBER_A, first);
        assertThat(registry.connectionCount(MEMBER_A)).isZero();

        assertThat(registry.tryRegister(MEMBER_A, CAP)).isNotNull();
        assertThat(registry.connectionCount(MEMBER_A)).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 연결 제거와 새 연결 등록이 동시에 일어나도 새 연결이 레지스트리에서 유실되지 않는다")
    void concurrentRemoveAndRegister_neverLosesNewConnection() throws Exception {
        // 제거(빈 키 정리)와 등록이 원자적이지 않으면, 새로 등록된 연결이 키와 함께 사라져 고아가 된다(리뷰 P2).
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                SseEmitter old = registry.tryRegister(MEMBER_B, CAP);
                assertThat(old).isNotNull();

                CountDownLatch start = new CountDownLatch(1);
                Future<?> remover = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    registry.remove(MEMBER_B, old);
                    return null;
                });
                Future<SseEmitter> registrar = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return registry.tryRegister(MEMBER_B, CAP);
                });

                start.countDown();
                remover.get(5, TimeUnit.SECONDS);
                SseEmitter fresh = registrar.get(5, TimeUnit.SECONDS);

                assertThat(fresh).isNotNull();
                // 방금 등록한 연결은 반드시 레지스트리에 남아 있어야 한다(전송·heartbeat 대상).
                assertThat(registry.connectionCount(MEMBER_B))
                        .as("round %d", round)
                        .isEqualTo(1);

                registry.remove(MEMBER_B, fresh); // 다음 라운드를 위해 정리
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("연결이 없는 수신자에게 전송해도 예외 없이 무시된다(저장이 원본, 전송은 부가 채널)")
    void send_toMemberWithoutConnection_isNoOp() {
        assertThatCode(() -> registry.send(MEMBER_A, "notification", "payload"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 회원의 여러 연결에 전송해도 모든 연결이 유지된다(전송 실패로 정리되지 않는다)")
    void send_toMultipleConnections_keepsAllConnections() {
        // 빌더 1회용 계약(연결마다 새로 만든다)은 이 단위 테스트로는 확인할 수 없다 — 레지스트리가 SseEmitter를
        // 내부에서 생성해 전송된 payload를 관찰할 수 없고, 잘못 재사용해도 덧붙는 빈 줄은 예외를 일으키지 않는다.
        // 그 계약은 send()의 주석과 구현으로 지키고, 여기서는 다중 연결 전송이 연결을 잃지 않는지만 고정한다.
        registry.tryRegister(MEMBER_A, 5);
        registry.tryRegister(MEMBER_A, 5);

        assertThatCode(() -> registry.send(MEMBER_A, "notification", "payload"))
                .doesNotThrowAnyException();

        assertThat(registry.connectionCount(MEMBER_A)).isEqualTo(2);
    }
}
