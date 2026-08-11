package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.config.PaymentReconcileProperties;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.scheduler.ReconcileLock;
import com.doctorpet.domain.payment.scheduler.ReconcileSummary;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 1 — 정산 배치 분기 로직 단위 검증(#35). 단건조회 결과(PAID/FAILED/PENDING/조회실패)에 따라
 * 후확정에 전달되는 ChargeOutcome과 알림 발행 여부를 확인한다. 조건부 확정의 실제 영속·락은 Level 3가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentReconcileServiceTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final Long RESERVATION_ID = 100L;
    private static final Long GUARDIAN_ID = 5L;
    private static final String MERCHANT_ID = "pay_x";
    private static final int AMOUNT = 50_000;
    private static final String LOCK_TOKEN = "tok";

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentChargeService paymentChargeService;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentNotificationPublisher notificationPublisher;
    @Mock private ReservationLookupPort reservationLookupPort;
    @Mock private ReconcileLock reconcileLock;
    // publishStuckNoticeIfPending(@Transactional, 행 락)을 프록시 경유로 호출하기 위한 자기참조. 단위 테스트에는
    // 프록시가 없으므로 getObject()가 실제 service를 돌려주게 해, 그 메서드가 findByIdForUpdate로 현재 상태를 재확인한다.
    @Mock private ObjectProvider<PaymentReconcileService> self;

    // JVM 기본 시간대에 의존하지 않도록 테스트도 고정 Clock을 주입한다(운영은 서울 기준 applicationClock).
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private PaymentReconcileService service;

    @BeforeEach
    void setUp() {
        PaymentReconcileProperties properties = new PaymentReconcileProperties();
        properties.setStaleAfterMs(120_000L);
        properties.setBatchSize(100);
        properties.setMaxAttempts(MAX_ATTEMPTS);
        service = new PaymentReconcileService(
                paymentRepository, paymentChargeService, paymentGateway, notificationPublisher,
                reservationLookupPort, reconcileLock, FIXED_CLOCK, properties, self);
        lenient().when(self.getObject()).thenReturn(service);
    }

    private PaymentChargeService.FinalizeResult applied(Payment payment) {
        return new PaymentChargeService.FinalizeResult(payment, true);
    }

    private PaymentChargeService.FinalizeResult notApplied(Payment payment) {
        return new PaymentChargeService.FinalizeResult(payment, false);
    }

    private Payment pendingTarget(int retryCount) {
        Payment p = Payment.pending(RESERVATION_ID, MERCHANT_ID, 7L, "VISA", "1234", AMOUNT);
        if (retryCount > 0) {
            p.remainPending(retryCount, "prev");
        }
        // 비영속 픽스처라 id가 없으면 finalizeOutcome(anyLong,...) 스텁이 매칭되지 않으므로 id를 부여한다.
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }

    private Payment resolved(PaymentStatus status) {
        Payment p = Payment.pending(RESERVATION_ID, MERCHANT_ID, 7L, "VISA", "1234", AMOUNT);
        if (status == PaymentStatus.PAID) {
            p.markPaid("PG-1", LocalDateTime.now());
        } else {
            p.markOfflineRequired("RECONCILE", 0);
        }
        ReflectionTestUtils.setField(p, "id", 1L);
        return p;
    }

    private void lockAcquired(List<Payment> targets) {
        given(reconcileLock.tryLock()).willReturn(Optional.of(LOCK_TOKEN));
        given(paymentRepository.findReconcileTargets(eq(PaymentStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(targets);
    }

    private ChargeOutcome captureOutcome() {
        ArgumentCaptor<ChargeOutcome> captor = ArgumentCaptor.forClass(ChargeOutcome.class);
        verify(paymentChargeService).finalizeOutcome(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("다른 인스턴스가 실행 중(락 미획득)이면 스킵하고 아무 것도 처리하지 않는다")
    void lockNotAcquired_skips() {
        given(reconcileLock.tryLock()).willReturn(Optional.empty());

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.locked()).isFalse();
        verify(paymentRepository, never())
                .findReconcileTargets(any(), any(), any());
    }

    @Test
    @DisplayName("단건조회 PAID(금액·pgId 일치)면 PAID로 확정하고 알림을 발행한다")
    void queryPaid_finalizesPaid() {
        lockAcquired(List.of(pendingTarget(0)));
        given(paymentGateway.query(MERCHANT_ID)).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PAID, "PG-1", AMOUNT));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(applied(resolved(PaymentStatus.PAID)));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, 1L, GUARDIAN_ID, 7L, true)));

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.paid()).isEqualTo(1);
        assertThat(captureOutcome().type()).isEqualTo(ChargeOutcome.Type.PAID);
        verify(notificationPublisher).publishChargeResult(
                eq(GUARDIAN_ID), eq(RESERVATION_ID), any(), eq(PaymentStatus.PAID), eq(AMOUNT));
        verify(reconcileLock).unlock(LOCK_TOKEN);
    }

    @Test
    @DisplayName("단건조회가 여전히 PENDING이고 재시도 여유가 있으면 재시도 수를 올려 PENDING을 유지한다(알림 없음)")
    void queryPending_underMax_remainsPending() {
        lockAcquired(List.of(pendingTarget(0)));
        given(paymentGateway.query(MERCHANT_ID)).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(notApplied(pendingTarget(1)));

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.stillPending()).isEqualTo(1);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.retryCount()).isEqualTo(1);
        // 일시적 PENDING(곧 확정될 상태)에는 결과 알림도, "결제 확인 중" 안내도 발행하지 않는다.
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), any(), any(), anyInt());
        verify(notificationPublisher, never()).publishPendingNotice(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("재시도 임계를 넘겨 STUCK(오래 미확정)이면 상태는 PENDING 유지, '결제 확인 중' 안내를 1회 발행한다")
    void queryPending_atMax_publishesStuckNotice() {
        // 재조회 임계를 넘겨도 승인 여부가 불확실하면 OFFLINE(이중결제 위험) 대신 PENDING을 유지한다.
        // 단, 무음이 CS로 이어지지 않도록 "결제 확인 중" 안내는 발행한다(상태는 그대로).
        lockAcquired(List.of(pendingTarget(MAX_ATTEMPTS)));
        given(paymentGateway.query(MERCHANT_ID)).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(notApplied(pendingTarget(MAX_ATTEMPTS)));
        // 발행 여부는 행 락 아래 현재 상태를 다시 읽어 판단한다 — 여전히 PENDING이라 발행한다.
        given(paymentRepository.findByIdForUpdate(anyLong())).willReturn(Optional.of(pendingTarget(MAX_ATTEMPTS)));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, 1L, GUARDIAN_ID, 7L, true)));

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.stillPending()).isEqualTo(1);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("RECONCILE_STUCK");
        // 상태 확정 알림은 없고(전이 없음), 대신 "결제 확인 중" 안내를 금액과 함께 발행한다.
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), any(), any(), anyInt());
        verify(notificationPublisher).publishPendingNotice(eq(GUARDIAN_ID), eq(RESERVATION_ID), any(), eq(AMOUNT));
    }

    @Test
    @DisplayName("STUCK 조회지만 행 락으로 다시 읽었을 때 이미 PAID로 확정됐으면 '결제 확인 중'을 발행하지 않는다")
    void queryStuck_butAlreadyConfirmed_doesNotPublishStuckNotice() {
        // resolveByQuery는 과거 조회 시점 기준 STUCK을 돌려주지만, 발행 직전 결제 행을 잠그고 현재 상태를 다시 읽는다.
        // 그 사이 청구 후확정·웹훅이 먼저 PAID로 전이했다면 findByIdForUpdate가 PAID를 보므로, 완료 알림 뒤에 뒤늦은
        // "결제 확인 중"이 나가지 않는다(리뷰 P1 — check-then-act 구간 제거).
        lockAcquired(List.of(pendingTarget(MAX_ATTEMPTS)));
        given(paymentGateway.query(MERCHANT_ID)).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(notApplied(pendingTarget(MAX_ATTEMPTS)));
        // 행 락으로 다시 읽으니 이미 PAID — 발행하지 않는다.
        given(paymentRepository.findByIdForUpdate(anyLong())).willReturn(Optional.of(resolved(PaymentStatus.PAID)));

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.stillPending()).isEqualTo(1);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.failureReason()).isEqualTo("RECONCILE_STUCK");
        // 잠그고 보니 이미 PAID라 "결제 확인 중"을 발행하지 않는다.
        verify(notificationPublisher, never()).publishPendingNotice(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("단건조회 FAILED면 OFFLINE_REQUIRED로 확정한다")
    void queryFailed_offlineRequired() {
        lockAcquired(List.of(pendingTarget(0)));
        given(paymentGateway.query(MERCHANT_ID)).willReturn(new PaymentQueryResult(GatewayPaymentStatus.FAILED, null, 0));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(applied(resolved(PaymentStatus.OFFLINE_REQUIRED)));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, 1L, GUARDIAN_ID, 7L, true)));

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.offlineRequired()).isEqualTo(1);
        assertThat(captureOutcome().failureReason()).isEqualTo("RECONCILE_FAILED");
    }

    @Test
    @DisplayName("단건조회 자체가 실패하면 확정하지 않고 PENDING을 유지한다(다음 배치 재시도)")
    void queryThrows_remainsPending() {
        lockAcquired(List.of(pendingTarget(0)));
        given(paymentGateway.query(MERCHANT_ID))
                .willThrow(new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null, "조회 실패"));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(notApplied(pendingTarget(0)));

        ReconcileSummary summary = service.reconcile();

        assertThat(summary.stillPending()).isEqualTo(1);
        assertThat(captureOutcome().failureReason()).isEqualTo("RECONCILE_QUERY_FAILED");
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), any(), any(), anyInt());
        verify(notificationPublisher, never()).publishPendingNotice(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("단건조회 PAID지만 금액이 다르면 OFFLINE 대신 PENDING을 유지한다(이중결제 금지)")
    void queryPaidAmountMismatch_staysPending() {
        // 조회는 PAID지만 금액이 다르면 PortOne이 이미 청구했을 수 있어 OFFLINE 대신 PENDING을 유지한다(이중결제 금지).
        lockAcquired(List.of(pendingTarget(0)));
        given(paymentGateway.query(MERCHANT_ID)).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PAID, "PG-1", AMOUNT + 1));
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willReturn(notApplied(pendingTarget(0)));

        service.reconcile();

        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("RECONCILE_AMOUNT_MISMATCH");
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), any(), any(), anyInt());
        verify(notificationPublisher, never()).publishPendingNotice(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("정산 대상 조회 임계는 주입된 Clock 기준으로 계산한다(JVM 기본 시간대에 의존하지 않는다)")
    void threshold_usesInjectedClock() {
        // updatedAt은 서울 기준 applicationClock으로 저장되는데 임계를 JVM 기본 TZ의 now()로 만들면 UTC JVM에서
        // 약 9시간 어긋난다. 고정 Clock을 주입해 임계가 (Clock now - staleAfter)로 정확히 계산됨을 증명한다.
        given(reconcileLock.tryLock()).willReturn(Optional.of(LOCK_TOKEN));
        given(paymentRepository.findReconcileTargets(eq(PaymentStatus.PENDING), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(List.of());

        service.reconcile();

        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(paymentRepository)
                .findReconcileTargets(eq(PaymentStatus.PENDING), thresholdCaptor.capture(), any(Pageable.class));
        LocalDateTime expected = LocalDateTime.now(FIXED_CLOCK).minus(Duration.ofMillis(120_000L));
        assertThat(thresholdCaptor.getValue()).isEqualTo(expected);
    }
}
