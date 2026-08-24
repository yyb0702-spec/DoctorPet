package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.scheduler.ReconcileLock;
import com.doctorpet.domain.payment.scheduler.ReconcileSummary;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.fake.FakePaymentGateway;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 결제 정산 배치 통합 검증(STRICT, #35). 실 MySQL에 저장된 PENDING 결제를 대상으로 단건조회(Fake)로
 * PAID/미확정 수렴, 재조회 백오프, 실 Redis 락으로 다중 인스턴스 단일 실행을 확인한다. 예약 port·알림은 목이다.
 * stale-after-ms=0으로 방금 저장한 PENDING도 대상이 되게 한다. 전체 컨텍스트(MySQL·Redis·env) 필요.
 */
@SpringBootTest(properties = "payment.reconcile.stale-after-ms=0")
class PaymentReconcileIntegrationTest {

    private static final Long HOSPITAL_ID = 6161L;
    private static final Long GUARDIAN_ID = 61L;
    private static final int AMOUNT = 50_000;

    @Autowired private PaymentReconcileService paymentReconcileService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentGateway fakePaymentGateway;
    @Autowired private ReconcileLock reconcileLock;

    @MockitoBean private ReservationLookupPort reservationLookupPort;
    @MockitoBean private PaymentNotificationPublisher notificationPublisher;

    private final List<Long> paymentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
    }

    @AfterEach
    void tearDown() {
        paymentIds.forEach(paymentRepository::deleteById);
    }

    @Test
    @DisplayName("단건조회가 PAID면 PENDING 결제를 PAID로 확정하고 알림을 1회 발행한다")
    void reconcile_pendingToPaid() {
        Payment target = persistPending();
        fakePaymentGateway.stubQueryResult(target.getMerchantPaymentId(), GatewayPaymentStatus.PAID, AMOUNT);

        ReconcileSummary summary = paymentReconcileService.reconcile();

        assertThat(summary.locked()).isTrue();
        assertThat(summary.paid()).isGreaterThanOrEqualTo(1);
        Payment reloaded = paymentRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reloaded.getPgPaymentId()).isNotNull();
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), eq(target.getReservationId()), eq(target.getId()), eq(PaymentStatus.PAID),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("단건조회가 여전히 미확정이면 PENDING을 유지하고 재시도 수를 올린다(알림 없음)")
    void reconcile_staysPending() {
        Payment target = persistPending();
        fakePaymentGateway.stubQueryResult(target.getMerchantPaymentId(), GatewayPaymentStatus.PENDING, 0);

        paymentReconcileService.reconcile();

        Payment reloaded = paymentRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getRetryCount()).isEqualTo(1);
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(notificationPublisher, never()).publishPendingNotice(anyLong(), anyLong(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("다른 인스턴스가 락을 쥐고 있으면 배치는 스킵된다(다중 인스턴스 단일 실행)")
    void reconcile_skipsWhenLockHeld() {
        Optional<String> held = reconcileLock.tryLock();
        assertThat(held).isPresent();
        try {
            ReconcileSummary summary = paymentReconcileService.reconcile();
            assertThat(summary.locked()).isFalse();
            assertThat(summary.scanned()).isZero();
        } finally {
            reconcileLock.unlock(held.get());
        }
    }

    @Test
    @DisplayName("금액불일치·불완전응답(수동 확인 대상)인 PENDING은 정산 조회 대상에서 제외한다(이중결제 금지)")
    void reconcileTargets_excludeUncertainPaidReasons() {
        // 이미 청구됐을 수 있는 불확실 결제는 재조회가 FAILED로 오면 OFFLINE 전환→이중결제가 될 수 있어, 자동 정산 대상에서
        // 빼고 운영자 수동 확인으로 남긴다. 청구 단계 사유(AMOUNT_MISMATCH·INVALID_PG_RESULT)뿐 아니라 정산 단계에서
        // 금액·pgId 불일치로 남긴 RECONCILE_AMOUNT_MISMATCH도 다음 배치에서 다시 선택되지 않아야 한다(PR #81 P1 후속 리뷰).
        Payment normal = persistPending();
        Payment mismatch = persistPendingWithReason("AMOUNT_MISMATCH");
        Payment invalid = persistPendingWithReason("INVALID_PG_RESULT");
        Payment reconcileMismatch = persistPendingWithReason("RECONCILE_AMOUNT_MISMATCH");

        List<Payment> targets = paymentRepository.findReconcileTargets(
                PaymentStatus.PENDING, LocalDateTime.now().plusYears(1), PageRequest.of(0, 100));
        List<Long> targetIds = targets.stream().map(Payment::getId).toList();

        assertThat(targetIds).contains(normal.getId());
        assertThat(targetIds).doesNotContain(mismatch.getId(), invalid.getId(), reconcileMismatch.getId());
    }

    @Test
    @DisplayName("renew는 내가 쥔 락의 임대를 유지하고, unlock은 내 토큰일 때만 원자적으로 해제한다")
    void lock_renewKeepsOwnership_and_unlockIsTokenScoped() {
        Optional<String> mine = reconcileLock.tryLock();
        assertThat(mine).isPresent();

        reconcileLock.renew(mine.get());                       // 여전히 내가 쥔 락이므로
        assertThat(reconcileLock.tryLock()).isEmpty();          // 다른 인스턴스는 획득 실패해야 한다

        reconcileLock.unlock("someone-else-token");             // 남의 토큰으로는 해제되지 않는다
        assertThat(reconcileLock.tryLock()).isEmpty();

        reconcileLock.unlock(mine.get());                       // 내 토큰으로만 해제된다
        Optional<String> reacquired = reconcileLock.tryLock();
        assertThat(reacquired).isPresent();
        reconcileLock.unlock(reacquired.get());
    }

    private Payment persistPending() {
        long reservationId = System.nanoTime();
        Payment payment = Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", AMOUNT);
        Payment saved = paymentRepository.saveAndFlush(payment);
        paymentIds.add(saved.getId());
        given(reservationLookupPort.findForCharge(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, 7L, true)));
        return saved;
    }

    // 청구 단계에서 불확실 사유로 PENDING에 남은 결제 픽스처. remainPending으로 failureReason만 세팅한다(상태는 PENDING).
    private Payment persistPendingWithReason(String failureReason) {
        long reservationId = System.nanoTime();
        Payment payment = Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", AMOUNT);
        payment.remainPending(0, failureReason);
        Payment saved = paymentRepository.saveAndFlush(payment);
        paymentIds.add(saved.getId());
        return saved;
    }
}
