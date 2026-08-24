package com.doctorpet.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Level 3 — 실제 Redis로 SSE 구독 티켓의 단기·1회성 계약을 검증한다(PR #106 리뷰 P2).
 *
 * <p>구독 경로(`GET /api/notifications/subscribe`)는 `EventSource`가 헤더 인증을 못 하므로 permitAll이고,
 * 접근 통제가 전적으로 이 저장소의 티켓 소비 로직(GET+DEL Lua)에 달려 있다. Mockito 슬라이스로는
 * 티켓이 진짜로 1회만 소비되는지·TTL이 실제로 걸리는지·동시 소비에서 정확히 하나만 이기는지를
 * 확인할 수 없어, 인증 경계의 회귀를 막기 위해 실제 Redis로 검증한다.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class SseTicketRepositoryIntegrationTest {

    private static final Long MEMBER_ID = 90001L;
    private static final String KEY_PREFIX = "sse-ticket:";

    @Autowired
    private SseTicketRepository ticketRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("발급한 티켓은 memberId로 소비되고, 값 자체에는 회원 식별자가 들어가지 않는다")
    void issue_thenConsume_resolvesMember() {
        String ticket = ticketRepository.issue(MEMBER_ID);

        // 티켓은 URL·로그에 남을 수 있으므로 그 자체로 식별 정보가 되지 않아야 한다.
        assertThat(ticket).doesNotContain(String.valueOf(MEMBER_ID));
        assertThat(ticketRepository.consume(ticket)).contains(MEMBER_ID);
    }

    @Test
    @DisplayName("티켓은 1회성이다 — 첫 소비만 성공하고 재소비는 실패하며 키도 남지 않는다")
    void consume_isSingleUse() {
        String ticket = ticketRepository.issue(MEMBER_ID);

        assertThat(ticketRepository.consume(ticket)).contains(MEMBER_ID);

        assertThat(ticketRepository.consume(ticket)).isEmpty();
        assertThat(redisTemplate.hasKey(KEY_PREFIX + ticket)).isFalse();
    }

    @Test
    @DisplayName("발급 시 TTL이 걸려 있고(30초 이하), 만료·존재하지 않는 티켓은 거절된다")
    void issue_appliesShortTtl_andExpiredTicketIsRejected() {
        String ticket = ticketRepository.issue(MEMBER_ID);

        Long ttl = redisTemplate.getExpire(KEY_PREFIX + ticket, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(30L);

        // 만료를 기다리지 않고 키를 지워 "만료된 티켓"과 동일한 상태를 만든다.
        redisTemplate.delete(KEY_PREFIX + ticket);
        assertThat(ticketRepository.consume(ticket)).isEmpty();

        assertThat(ticketRepository.consume("존재하지-않는-티켓")).isEmpty();
    }

    @Test
    @DisplayName("같은 티켓을 동시에 소비해도 정확히 하나만 성공한다(GET+DEL 원자 실행)")
    void consume_concurrent_onlyOneWins() {
        String ticket = ticketRepository.issue(MEMBER_ID);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Long>>> results = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return ticketRepository.consume(ticket);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long winners = 0;
            for (Future<Optional<Long>> result : results) {
                Optional<Long> resolved = result.get(5, TimeUnit.SECONDS);
                if (resolved.isPresent()) {
                    assertThat(resolved).contains(MEMBER_ID);
                    winners++;
                }
            }

            assertThat(winners).isEqualTo(1);
            assertThat(redisTemplate.hasKey(KEY_PREFIX + ticket)).isFalse();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 소비 검증이 중단됐습니다", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("동시 소비 검증이 실패했습니다", exception);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("발급한 티켓끼리 값이 겹치지 않고, 각각 독립적으로 소비된다")
    void issue_producesDistinctTickets() {
        String first = ticketRepository.issue(MEMBER_ID);
        String second = ticketRepository.issue(MEMBER_ID);

        assertThat(first).isNotEqualTo(second);
        assertThat(ticketRepository.consume(first)).contains(MEMBER_ID);
        // 하나를 소비해도 다른 티켓은 그대로 유효하다(다중 탭·기기 구독).
        assertThat(ticketRepository.consume(second)).contains(MEMBER_ID);
    }
}
