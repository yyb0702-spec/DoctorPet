package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Level 3 — 결제 후확정 동시성 통합 검증(STRICT, PR #81 P1). 청구 후확정(#34)과 정산 확정(#35)은 모두
 * PaymentChargeService.finalizeOutcome을 통해 상태를 전이하므로, 같은 PENDING 결제에 두 확정을 동시에 실행해
 * WHERE status='PENDING' 조건부 UPDATE가 상태 전이를 정확히 1건으로 보장하는지(덮어쓰기·이중 확정 없음, 500 미발생)를
 * 실제 MySQL로 검증한다. 알림 1회 보장은 "applied일 때만 발행"으로 이어지며 각 서비스 단위 테스트가 담당한다.
 * 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class PaymentFinalizeConcurrencyIntegrationTest {

    private static final int AMOUNT = 50_000;

    @Autowired private PaymentChargeService paymentChargeService;
    @Autowired private PaymentRepository paymentRepository;

    private Long paymentId;

    @AfterEach
    void tearDown() {
        if (paymentId != null) {
            paymentRepository.deleteById(paymentId);
        }
    }

    @Test
    @DisplayName("같은 PENDING 결제에 후확정(PAID)과 정산(OFFLINE_REQUIRED)이 동시에 들어와도 상태 전이는 정확히 1건이고 예외가 없다")
    void concurrentFinalize_appliesExactlyOnce() throws InterruptedException {
        long reservationId = System.nanoTime();
        Payment saved = paymentRepository.saveAndFlush(
                Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", AMOUNT));
        paymentId = saved.getId();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<PaymentChargeService.FinalizeResult> paidResult = new AtomicReference<>();
        AtomicReference<PaymentChargeService.FinalizeResult> offlineResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> runFinalize(start, done, failure, paidResult,
                ChargeOutcome.paid("PG-1", LocalDateTime.now())));
        pool.submit(() -> runFinalize(start, done, failure, offlineResult,
                ChargeOutcome.offlineRequired("RECONCILE_FAILED", 0)));

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 어떤 확정도 500(예외)으로 새지 않는다.
        assertThat(failure.get()).isNull();

        // 상태 전이는 정확히 1건 — 한 호출만 applied=true, 다른 호출은 이미 확정돼 applied=false.
        int appliedCount = (paidResult.get().applied() ? 1 : 0) + (offlineResult.get().applied() ? 1 : 0);
        assertThat(appliedCount).isEqualTo(1);

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getStatus()).isIn(PaymentStatus.PAID, PaymentStatus.OFFLINE_REQUIRED);
        // 실제 저장된 최종 상태는 applied=true였던 호출의 결과와 일치해야 한다(덮어쓰기 없음).
        PaymentStatus expectedWinner = paidResult.get().applied() ? PaymentStatus.PAID : PaymentStatus.OFFLINE_REQUIRED;
        assertThat(reloaded.getStatus()).isEqualTo(expectedWinner);
    }

    private void runFinalize(
            CountDownLatch start,
            CountDownLatch done,
            AtomicReference<Throwable> failure,
            AtomicReference<PaymentChargeService.FinalizeResult> sink,
            ChargeOutcome outcome
    ) {
        try {
            start.await();
            sink.set(paymentChargeService.finalizeOutcome(paymentId, outcome));
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            done.countDown();
        }
    }
}
