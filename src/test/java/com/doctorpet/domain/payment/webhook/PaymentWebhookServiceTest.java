package com.doctorpet.domain.payment.webhook;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentWebhook;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.repository.PaymentWebhookRepository;
import com.doctorpet.domain.payment.service.PaymentReconcileService;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Level 1 — 결제 웹훅 처리 단위 검증(#48, PR #96 리뷰 반영). 멱등키는 webhook_id다(같은 이벤트 재전송 흡수,
 * event_type이 같아도 서로 다른 이벤트는 각각 처리). 상태는 웹훅 body가 아니라 재조회(reconcilePayment)로
 * 확정하고, 재조회는 claimForReconcile 선점에 성공한 한 스레드만 실행한다 — 처리 전 실패는 선점 해제로 재구동,
 * 진행 중 재수신은 선점 실패로 재조회를 건너뛴다. 알 수 없는 결제·식별 불가·미지원 이벤트 처리와, 중복키만
 * 흡수하고 그 외 무결성 오류는 전파하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

    private static final String WEBHOOK_ID = "wh_1";
    private static final String EVENT_TYPE = "Transaction.Paid";
    private static final String MERCHANT_ID = "pay_1";
    private static final Long PAYMENT_ID = 10L;

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentWebhookRepository webhookRepository;
    @Mock private PaymentReconcileService reconcileService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC);

    private PaymentWebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new PaymentWebhookService(paymentRepository, webhookRepository, reconcileService, clock);
    }

    private Payment payment() {
        Payment payment = mock(Payment.class);
        given(payment.getId()).willReturn(PAYMENT_ID);
        return payment;
    }

    private PaymentWebhook webhook(String eventType) {
        return PaymentWebhook.received(WEBHOOK_ID, PAYMENT_ID, eventType, LocalDateTime.now(clock));
    }

    @Test
    @DisplayName("알려진 결제의 새 이벤트는 수신 기록 후 선점·재조회로 상태를 확정하고 처리 완료를 찍는다")
    void knownPayment_recordsAndReconciles() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.empty());
        given(webhookRepository.claimForReconcile(eq(WEBHOOK_ID), any())).willReturn(1);

        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository).saveAndFlush(any()); // 수신 기록(insert) 1회
        verify(reconcileService).reconcilePayment(payment);
        verify(webhookRepository).markProcessed(eq(WEBHOOK_ID), any());
    }

    @Test
    @DisplayName("이미 처리된 이벤트(같은 webhook_id 재수신)는 선점·재조회하지 않는다(멱등)")
    void alreadyProcessed_skips() {
        Payment payment = payment();
        PaymentWebhook processed = webhook(EVENT_TYPE);
        processed.markProcessed(LocalDateTime.now(clock));
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.of(processed));

        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository, never()).claimForReconcile(any(), any());
        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("처리 전 실패로 남은 미처리 웹훅은 재전송 시 선점·재구동해 재조회하고 완료를 찍는다")
    void unprocessedRedelivery_redrives() {
        Payment payment = payment();
        PaymentWebhook unprocessed = webhook(EVENT_TYPE); // processed_at = null
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.of(unprocessed));
        given(webhookRepository.claimForReconcile(eq(WEBHOOK_ID), any())).willReturn(1);

        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID);

        verify(reconcileService).reconcilePayment(payment);
        verify(webhookRepository).markProcessed(eq(WEBHOOK_ID), any());
        verify(webhookRepository, never()).saveAndFlush(any()); // 재구동은 재삽입하지 않는다
    }

    @Test
    @DisplayName("첫 수신이 재조회 중일 때 같은 webhook_id가 다시 오면 선점에 실패해 재조회를 건너뛴다")
    void concurrentInProgress_claimFails_skipsReconcile() {
        Payment payment = payment();
        PaymentWebhook unprocessed = webhook(EVENT_TYPE);
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.of(unprocessed));
        // 다른 스레드가 이미 선점 중(또는 그 사이 처리 완료) → 선점 조건부 UPDATE 0건.
        given(webhookRepository.claimForReconcile(eq(WEBHOOK_ID), any())).willReturn(0);

        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID);

        verify(reconcileService, never()).reconcilePayment(any());
        verify(webhookRepository, never()).markProcessed(any(), any());
        verify(webhookRepository, never()).releaseReconcileClaim(any());
    }

    @Test
    @DisplayName("재조회가 실패하면 선점을 해제하고 처리 완료를 찍지 않아 재전송 때 재구동된다")
    void reconcileFails_releasesClaimAndDoesNotMarkProcessed() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.empty());
        given(webhookRepository.claimForReconcile(eq(WEBHOOK_ID), any())).willReturn(1);
        willThrow(new RuntimeException("reconcile 실패")).given(reconcileService).reconcilePayment(payment);

        assertThatThrownBy(() -> webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID))
                .isInstanceOf(RuntimeException.class);

        verify(webhookRepository).releaseReconcileClaim(WEBHOOK_ID);
        verify(webhookRepository, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("동시 중복 수신이 UNIQUE(webhook_id) 위반이면 1회만 반영하고 재조회하지 않는다")
    void concurrentDuplicate_uniqueViolation_skips() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.empty());
        // MySQL 중복키: SQLState 23000 + vendor 코드 1062(Duplicate entry).
        given(webhookRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException(
                "dup", new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062)));

        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository, never()).claimForReconcile(any(), any());
        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("중복키가 아닌 무결성 위반(예: NOT NULL)은 삼키지 않고 다시 던진다")
    void nonUniqueViolation_rethrown() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.empty());
        // MySQL NOT NULL 위반: SQLState 23000 + vendor 코드 1048 → 중복이 아니므로 전파돼야 한다.
        given(webhookRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException(
                "not-null", new SQLException("Column cannot be null", "23000", 1048)));

        assertThatThrownBy(() -> webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("지원하지 않는 이벤트는 감사 기록만 남기고 선점·재조회하지 않는다")
    void unsupportedEvent_recordsButSkipsReconcile() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.findByWebhookId(WEBHOOK_ID)).willReturn(Optional.empty());

        webhookService.handle(WEBHOOK_ID, "BillingKey.Updated", MERCHANT_ID);

        verify(reconcileService, never()).reconcilePayment(any());
        verify(webhookRepository, never()).claimForReconcile(any(), any());
        verify(webhookRepository).saveAndFlush(any()); // 감사 기록(insert)
        verify(webhookRepository).markProcessed(eq(WEBHOOK_ID), any()); // 처리 완료
    }

    @Test
    @DisplayName("알 수 없는 결제(merchantPaymentId 미매칭)는 상태를 바꾸지 않고 무시한다")
    void unknownPayment_ignored() {
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.empty());

        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository, never()).saveAndFlush(any());
        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("결제 식별자·이벤트 타입이 없으면(결제 무관 이벤트) 무시한다")
    void missingIdentifiers_ignored() {
        webhookService.handle(WEBHOOK_ID, null, null);
        webhookService.handle(WEBHOOK_ID, EVENT_TYPE, null);

        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("webhook-id가 없으면 멱등 보장 불가로 무시한다")
    void missingWebhookId_ignored() {
        webhookService.handle(null, EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository, never()).saveAndFlush(any());
        verify(reconcileService, never()).reconcilePayment(any());
    }
}
