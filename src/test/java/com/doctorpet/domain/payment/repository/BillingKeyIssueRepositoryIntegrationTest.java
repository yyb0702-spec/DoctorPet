package com.doctorpet.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Level 3 — 실제 Redis Lua GET+DEL로 빌링키 발급 시도의 1회성 소비·TTL 만료를 검증한다.
 * Mockito로는 동시 소비에서 정확히 한 요청만 값을 얻는 원자성을 확인할 수 없다.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost:8080/api/auth/verify-email",
        "mail.password-reset.base-url=http://localhost:3000/reset-password"
})
class BillingKeyIssueRepositoryIntegrationTest {

    private static final Long MEMBER_ID = 2_040L;
    private static final int CONCURRENT_CONSUMERS = 8;
    private static final String KEY_PREFIX = "payment-method-issue:";

    @Autowired
    private BillingKeyIssueRepository billingKeyIssueRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> issuedIssueIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        issuedIssueIds.forEach(issueId -> redisTemplate.delete(KEY_PREFIX + issueId));
    }

    @Test
    void 동일_issueId를_동시에_소비해도_정확히_한_요청만_성공한다() throws Exception {
        String issueId = issue();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CONSUMERS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_CONSUMERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<Long>>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < CONCURRENT_CONSUMERS; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return billingKeyIssueRepository.consume(issueId);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successfulConsumers = 0;
            for (Future<Optional<Long>> future : futures) {
                Optional<Long> consumed = future.get(5, TimeUnit.SECONDS);
                if (consumed.isPresent()) {
                    successfulConsumers++;
                    assertThat(consumed).contains(MEMBER_ID);
                }
            }

            assertThat(successfulConsumers).isEqualTo(1);
            assertThat(billingKeyIssueRepository.consume(issueId)).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void TTL이_만료된_issueId는_소비할_수_없다() {
        String issueId = issue();
        String key = KEY_PREFIX + issueId;

        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 600L);
        // 10분을 실제로 기다리지 않고 Redis의 만료 처리 자체를 즉시 실행해 TTL 이후 소비 경로를 검증한다.
        assertThat(redisTemplate.expire(key, Duration.ZERO)).isTrue();

        assertThat(billingKeyIssueRepository.consume(issueId)).isEmpty();
    }

    private String issue() {
        String issueId = billingKeyIssueRepository.issue(MEMBER_ID);
        issuedIssueIds.add(issueId);
        return issueId;
    }
}
