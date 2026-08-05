package com.doctorpet.domain.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentWebhook;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.repository.PaymentWebhookRepository;
import com.doctorpet.domain.payment.service.PaymentReconcileService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 결제 웹훅 멱등 제약을 실제 MySQL로 검증(STRICT, SA §9-4·§4, PR #96 리뷰). 단위 테스트는 Mockito가
 * 지어낸 SQLException으로 판별 로직만 확인하므로, 실제 DDL에 uk_payment_webhooks_webhook_id가 생성되는지와
 * 동시 중복 수신이 1건으로 막히는지는 확인하지 못한다. 여기서는 (1) 같은 webhook_id 동시 저장이 정확히 1건만
 * 성립하는지, (2) 같은 payment_id·event_type이어도 webhook_id가 다르면 각각 저장되는지, (3) 서비스가 처리 완료/
 * 미처리 재수신을 processed_at 기준으로 구분해 재조회를 중복 실행하지 않고 미처리는 재구동하는지를 확인한다.
 * reconcilePayment는 외부 재조회(PortOne)라 목으로 대체해 호출 횟수만 관측한다. 전체 컨텍스트(MySQL·Redis·env)가
 * 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class PaymentWebhookIntegrationTest {

    private static final String EVENT_TYPE = "Transaction.Paid";
    private static final int AMOUNT = 50_000;

    @Autowired private PaymentWebhookService webhookService;
    @Autowired private PaymentWebhookRepository webhookRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private Clock clock;

    @MockitoBean private PaymentReconcileService reconcileService;

    private final List<String> createdWebhookIds = new ArrayList<>();
    private Long paymentId;

    @AfterEach
    void tearDown() {
        for (String webhookId : createdWebhookIds) {
            webhookRepository.findByWebhookId(webhookId).ifPresent(webhookRepository::delete);
        }
        if (paymentId != null) {
            paymentRepository.deleteById(paymentId);
        }
    }

    private String uniqueWebhookId() {
        String webhookId = "wh_" + System.nanoTime();
        createdWebhookIds.add(webhookId);
        return webhookId;
    }

    private Payment persistPayment() {
        long reservationId = System.nanoTime();
        Payment saved = paymentRepository.saveAndFlush(
                Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", AMOUNT));
        paymentId = saved.getId();
        return saved;
    }

    @Test
    @DisplayName("같은 webhook_id를 동시에 저장하면 UNIQUE(webhook_id)로 정확히 1건만 성립한다")
    void concurrentSameWebhookId_persistsExactlyOne() throws InterruptedException {
        String webhookId = uniqueWebhookId();
        long paymentRef = System.nanoTime();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger violation = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    webhookRepository.saveAndFlush(PaymentWebhook.received(
                            webhookId, paymentRef, EVENT_TYPE, LocalDateTime.now(clock)));
                    success.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    violation.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(violation.get()).isEqualTo(threads - 1);
        assertThat(webhookRepository.findByWebhookId(webhookId)).isPresent();
    }

    @Test
    @DisplayName("같은 payment_id·event_type이어도 webhook_id가 다르면 각각 저장된다")
    void samePaymentAndEventDifferentWebhookId_persistsBoth() {
        String firstWebhookId = uniqueWebhookId();
        String secondWebhookId = uniqueWebhookId();
        long paymentRef = System.nanoTime();

        webhookRepository.saveAndFlush(PaymentWebhook.received(
                firstWebhookId, paymentRef, EVENT_TYPE, LocalDateTime.now(clock)));
        webhookRepository.saveAndFlush(PaymentWebhook.received(
                secondWebhookId, paymentRef, EVENT_TYPE, LocalDateTime.now(clock)));

        assertThat(webhookRepository.findByWebhookId(firstWebhookId)).isPresent();
        assertThat(webhookRepository.findByWebhookId(secondWebhookId)).isPresent();
    }

    @Test
    @DisplayName("이미 처리된 웹훅을 같은 webhook_id로 재수신하면 재조회를 다시 실행하지 않는다")
    void processedWebhook_reReceived_doesNotReconcileAgain() {
        Payment payment = persistPayment();
        String webhookId = uniqueWebhookId();

        webhookService.handle(webhookId, EVENT_TYPE, payment.getMerchantPaymentId());
        webhookService.handle(webhookId, EVENT_TYPE, payment.getMerchantPaymentId());

        // 재조회는 최초 1회만. 두 번째 재수신은 processed_at이 찍혀 있어 건너뛴다.
        verify(reconcileService, times(1)).reconcilePayment(any());
        PaymentWebhook stored = webhookRepository.findByWebhookId(webhookId).orElseThrow();
        assertThat(stored.isProcessed()).isTrue();
    }

    @Test
    @DisplayName("수신만 되고 처리 전 실패로 남은 웹훅은 재전송 시 재구동해 재조회하고 처리 완료를 찍는다")
    void unprocessedWebhook_reReceived_reDrivesReconcile() {
        Payment payment = persistPayment();
        String webhookId = uniqueWebhookId();
        // 앞선 수신에서 기록만 되고 재조회 전에 실패한 상태(processed_at=null)를 직접 만든다.
        webhookRepository.saveAndFlush(PaymentWebhook.received(
                webhookId, payment.getId(), EVENT_TYPE, LocalDateTime.now(clock)));

        webhookService.handle(webhookId, EVENT_TYPE, payment.getMerchantPaymentId());

        verify(reconcileService, times(1)).reconcilePayment(any());
        PaymentWebhook stored = webhookRepository.findByWebhookId(webhookId).orElseThrow();
        assertThat(stored.isProcessed()).isTrue();
    }

    @Test
    @DisplayName("첫 수신이 재조회 중일 때 같은 webhook_id가 다시 들어와도 재조회는 정확히 1회만 실행된다")
    void concurrentReceiptWhileReconciling_reconcilesExactlyOnce() throws InterruptedException {
        Payment payment = persistPayment();
        String webhookId = uniqueWebhookId();

        // 첫 수신의 reconcile을 게이트에서 붙잡아 '재조회 진행 중' 상태를 만든다. 그 사이 두 번째 수신을 보낸다.
        CountDownLatch reconcileEntered = new CountDownLatch(1);
        CountDownLatch releaseReconcile = new CountDownLatch(1);
        AtomicInteger reconcileCalls = new AtomicInteger();
        willAnswer(invocation -> {
            reconcileCalls.incrementAndGet();
            reconcileEntered.countDown();
            releaseReconcile.await();
            return null;
        }).given(reconcileService).reconcilePayment(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            webhookService.handle(webhookId, EVENT_TYPE, payment.getMerchantPaymentId());
            return null;
        });
        // 첫 수신이 선점 후 reconcile에 진입할 때까지 기다린 뒤 두 번째 수신을 보낸다.
        assertThat(reconcileEntered.await(10, TimeUnit.SECONDS)).isTrue();
        pool.submit(() -> {
            webhookService.handle(webhookId, EVENT_TYPE, payment.getMerchantPaymentId());
            return null;
        });
        // 두 번째 수신은 선점 실패로 즉시 끝난다(선점은 성공 시 해제되지 않으므로 타이밍과 무관). 게이트를
        // 풀어 첫 수신도 완료시킨다.
        releaseReconcile.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 진행 중 재수신은 선점(claimForReconcile)에 실패하므로 재조회는 정확히 1회.
        assertThat(reconcileCalls.get()).isEqualTo(1);
        PaymentWebhook stored = webhookRepository.findByWebhookId(webhookId).orElseThrow();
        assertThat(stored.isProcessed()).isTrue();
    }
}
