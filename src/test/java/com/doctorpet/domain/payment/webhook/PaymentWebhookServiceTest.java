package com.doctorpet.domain.payment.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.repository.PaymentWebhookRepository;
import com.doctorpet.domain.payment.service.PaymentReconcileService;
import java.time.Clock;
import java.time.Instant;
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
 * Level 1 — 결제 웹훅 처리 단위 검증(#48). 멱등((payment_id,event_type) 중복 1회만 반영)과, 상태는 웹훅 body가
 * 아니라 재조회(reconcilePayment)로 확정하는지, 알 수 없는 결제·식별 불가 이벤트는 무시하는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceTest {

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

    @Test
    @DisplayName("알려진 결제의 새 이벤트는 수신 기록 후 재조회로 상태를 확정한다")
    void knownPayment_recordsAndReconciles() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.existsByPaymentIdAndEventType(PAYMENT_ID, EVENT_TYPE)).willReturn(false);

        webhookService.handle(EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository).saveAndFlush(any());
        verify(reconcileService).reconcilePayment(payment);
    }

    @Test
    @DisplayName("이미 처리한 이벤트(사전 체크)는 재조회하지 않는다(멱등)")
    void duplicateEvent_skips() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.existsByPaymentIdAndEventType(PAYMENT_ID, EVENT_TYPE)).willReturn(true);

        webhookService.handle(EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository, never()).saveAndFlush(any());
        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("동시 중복 수신이 UNIQUE 위반이면 1회만 반영하고 재조회하지 않는다")
    void concurrentDuplicate_uniqueViolation_skips() {
        Payment payment = payment();
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.of(payment));
        given(webhookRepository.existsByPaymentIdAndEventType(PAYMENT_ID, EVENT_TYPE)).willReturn(false);
        given(webhookRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("dup"));

        webhookService.handle(EVENT_TYPE, MERCHANT_ID);

        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("알 수 없는 결제(merchantPaymentId 미매칭)는 상태를 바꾸지 않고 무시한다")
    void unknownPayment_ignored() {
        given(paymentRepository.findByMerchantPaymentId(MERCHANT_ID)).willReturn(Optional.empty());

        webhookService.handle(EVENT_TYPE, MERCHANT_ID);

        verify(webhookRepository, never()).saveAndFlush(any());
        verify(reconcileService, never()).reconcilePayment(any());
    }

    @Test
    @DisplayName("결제 식별자·이벤트 타입이 없으면(결제 무관 이벤트) 무시한다")
    void missingIdentifiers_ignored() {
        webhookService.handle(null, null);
        webhookService.handle(EVENT_TYPE, null);

        verify(reconcileService, never()).reconcilePayment(any());
    }
}
