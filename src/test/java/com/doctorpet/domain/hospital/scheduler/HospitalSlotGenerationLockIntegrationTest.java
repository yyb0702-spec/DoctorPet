package com.doctorpet.domain.hospital.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ai.openai.api-key=test-key",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class HospitalSlotGenerationLockIntegrationTest {

    @Autowired
    private HospitalSlotGenerationLock lock;

    @Test
    void onlyOneInstanceAcquiresSlotGenerationLock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Optional<String>> first = executor.submit(() ->
                lock.executeIfAcquired(() -> {
                    acquired.countDown();
                    await(release);
                    return "first";
                })
        );

        assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
        Optional<String> second = lock.executeIfAcquired(() -> "second");
        release.countDown();

        assertThat(second).isEmpty();
        assertThat(first.get(10, TimeUnit.SECONDS)).contains("first");
        executor.shutdown();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("슬롯 생성 잠금 해제를 기다리다 시간 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("슬롯 생성 잠금 대기 중 인터럽트가 발생했습니다.", exception);
        }
    }
}
