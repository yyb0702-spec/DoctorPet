package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.config.PaymentReconcileProperties;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.scheduler.ReconcileLock;
import com.doctorpet.domain.payment.scheduler.ReconcileSummary;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  결제 정산(reconcile) 배치(#35, SA §9-7·§9-4). 타임아웃 등으로 PENDING에 남은 결제를 주기적으로 단건조회해
  최종 상태(PAID/OFFLINE_REQUIRED)로 수렴시킨다. 이 클래스는 트랜잭션을 열지 않는다 — 외부 조회(PortOne)는 트랜잭션
  밖에서 하고, 상태 확정은 #34의 PaymentChargeService.finalizeOutcome(짧은 트랜잭션)을 재사용한다. 알림도 커밋 이후 발행한다.

  분기(건별):
  - 조회 PAID(금액·pgId 일치) → PAID 확정
  - 조회 PAID지만 금액·pgId 불일치 → RECONCILE_AMOUNT_MISMATCH로 PENDING 유지(이미 청구됐을 수 있어 오프라인 금지, 수동 확인 대상)
  - 조회 FAILED → OFFLINE_REQUIRED 확정
  - 조회 PENDING(여전히 미확정) → 재시도 수 +1로 PENDING 유지. 임계(max-attempts) 초과여도 승인 여부가 불확실하면
    OFFLINE(이중결제 위험) 대신 RECONCILE_STUCK로 PENDING을 유지해 운영자 확인 대상으로 남긴다(자동 현장수납 전환 금지)
  - 조회 실패(게이트웨이 예외) → PENDING 유지(다음 배치 재시도), 재시도 수는 올리지 않음
  다중 인스턴스 동시 실행은 ReconcileLock(Redis)으로 막는다 — 배치가 길어져도 처리 중 lease를 갱신(renew)해 만료로
  다른 인스턴스가 끼어드는 것을 막고, 락 해제는 내 토큰일 때만 원자적으로 한다. 락은 정산-정산 경합만 막으므로,
  같은 락을 쓰지 않는 청구 후확정(#34)과의 경합은 finalizeOutcome의 WHERE status='PENDING' 조건부 UPDATE가 막는다 —
  전이가 1건만 성립하고, 이 호출이 실제로 전이시켰을 때(applied)만 알림을 발행해 중복 발행을 막는다(PR #81 P1).
 */
@Slf4j
@Service
public class PaymentReconcileService {

    // 재조회 상한을 넘겨 오래 미확정으로 남긴 PENDING의 사유(ChargeOutcome.failureReason). 이 사유일 때만
    // "결제 확인 중" 안내를 발행하므로, resolveStillPending의 표시와 reconcileOne의 판별이 같은 값을 쓰게 상수로 둔다.
    private static final String RECONCILE_STUCK = "RECONCILE_STUCK";

    private final PaymentRepository paymentRepository;
    private final PaymentChargeService paymentChargeService;
    private final PaymentGateway paymentGateway;
    private final PaymentNotificationPublisher notificationPublisher;
    private final ReservationLookupPort reservationLookupPort;
    private final ReconcileLock reconcileLock;
    // JpaAuditing의 updatedAt과 같은 서울 기준 Clock(applicationClock). 조회 임계·확정 시각을 JVM 기본 시간대가
    // 아니라 이 Clock으로 계산해, 운영·CI JVM이 UTC여도 updatedAt과 임계값의 시간대가 어긋나지 않게 한다.
    private final Clock clock;
    private final PaymentReconcileProperties properties;
    // publishStuckNoticeIfPending(@Transactional, 행 락)을 프록시 경유로 호출하기 위한 자기참조. 같은 빈 내부 호출은
    // 프록시를 우회해 @Transactional이 무시되므로, 순환 초기화 없는 ObjectProvider로 지연 주입한다.
    private final ObjectProvider<PaymentReconcileService> self;

    public PaymentReconcileService(
            PaymentRepository paymentRepository,
            PaymentChargeService paymentChargeService,
            PaymentGateway paymentGateway,
            PaymentNotificationPublisher notificationPublisher,
            ReservationLookupPort reservationLookupPort,
            ReconcileLock reconcileLock,
            Clock clock,
            PaymentReconcileProperties properties,
            ObjectProvider<PaymentReconcileService> self
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentChargeService = paymentChargeService;
        this.paymentGateway = paymentGateway;
        this.notificationPublisher = notificationPublisher;
        this.reservationLookupPort = reservationLookupPort;
        this.reconcileLock = reconcileLock;
        this.clock = clock;
        this.properties = properties;
        this.self = self;
    }

    /** 정산 배치 1회 실행. 다른 인스턴스가 실행 중이면 스킵한다. */
    public ReconcileSummary reconcile() {
        Optional<String> lock = reconcileLock.tryLock();
        if (lock.isEmpty()) {
            return ReconcileSummary.skipped();
        }
        try {
            LocalDateTime threshold = LocalDateTime.now(clock).minus(Duration.ofMillis(properties.getStaleAfterMs()));
            List<Payment> targets = paymentRepository.findReconcileTargets(
                    PaymentStatus.PENDING, threshold, PageRequest.of(0, properties.getBatchSize()));

            int paid = 0;
            int offlineRequired = 0;
            int stillPending = 0;
            int errored = 0;
            for (Payment target : targets) {
                // 외부 단건조회는 느릴 수 있어, 건별로 락 임대를 갱신해 배치가 길어져도 만료로 다른 인스턴스가 끼어들지 않게 한다.
                reconcileLock.renew(lock.get());
                try {
                    switch (reconcileOne(target)) {
                        case PAID -> paid++;
                        case OFFLINE_REQUIRED -> offlineRequired++;
                        case PENDING -> stillPending++;
                    }
                } catch (RuntimeException e) {
                    // 한 건의 실패가 배치 전체를 멈추지 않게 격리한다. 그 건은 다음 배치에서 다시 시도된다.
                    log.warn("결제 정산 처리 실패 paymentId={}", target.getId(), e);
                    errored++;
                }
            }
            return new ReconcileSummary(true, targets.size(), paid, offlineRequired, stillPending, errored);
        } finally {
            reconcileLock.unlock(lock.get());
        }
    }

    /**
     * 단건 정산(웹훅·수동 트리거 재사용, #48). 배치 락·타임아웃 임계 밖에서 한 건을 재조회→멱등 확정한다.
     * 웹훅은 "트리거"일 뿐이라 상태는 여기서 재조회(PortOne)로 확정한다 — 웹훅 body의 상태를 신뢰하지 않는다.
     * finalizeOutcome의 조건부 UPDATE가 청구 후확정·정산 배치와의 경합을 멱등하게 흡수한다(applied일 때만 알림).
     */
    public ChargeOutcome.Type reconcilePayment(Payment target) {
        return reconcileOne(target);
    }

    private ChargeOutcome.Type reconcileOne(Payment target) {
        ChargeOutcome outcome = resolveByQuery(target);
        PaymentChargeService.FinalizeResult result = paymentChargeService.finalizeOutcome(target.getId(), outcome);
        // 이 호출이 실제로 상태를 전이시켰을 때만 발행한다 — 청구 후확정이 먼저 확정했다면(조건부 UPDATE 0건) 중복 발행하지 않는다.
        if (result.applied()) {
            publishResolved(result.payment());
        } else if (RECONCILE_STUCK.equals(outcome.failureReason())) {
            // 상태 전이는 없지만(PENDING 유지) 오래 미확정으로 STUCK 확정된 경우 — 보호자 무음을 해소하기 위해
            // "결제 확인 중" 안내를 발행한다. 최초/일시적 PENDING(RECONCILE_UNCONFIRMED 등)은 여기 오지 않는다.
            // outcome은 과거 조회 시점의 STUCK이라 그 사이 다른 경로가 PAID/OFFLINE로 확정했을 수 있으므로, 발행 여부는
            // 결제 행을 잠그고 현재 상태를 다시 확인하는 트랜잭션(publishStuckNoticeIfPending) 안에서 원자적으로 판단한다.
            // self 프록시로 호출해 @Transactional(행 락) 경계가 적용되게 한다.
            self.getObject().publishStuckNoticeIfPending(target.getId());
        }
        return outcome.type();
    }

    private ChargeOutcome resolveByQuery(Payment target) {
        try {
            PaymentQueryResult query = paymentGateway.query(target.getMerchantPaymentId());
            return switch (query.status()) {
                case PAID -> (query.paidAmount() == target.getAmount() && hasText(query.pgPaymentId()))
                        ? ChargeOutcome.paid(query.pgPaymentId(), LocalDateTime.now(clock))
                        // 금액 불일치·불완전 응답은 PortOne이 이미 청구했을 수 있어, OFFLINE(현장 재수납=이중결제) 대신
                        // PENDING을 유지해 운영자 수동 확인 대상으로 둔다(#34 리뷰 교정과 동일 원칙 — 불확실 시 오프라인 금지).
                        : ChargeOutcome.pending(target.getRetryCount(), "RECONCILE_AMOUNT_MISMATCH");
                // 성공이 아님이 확실한(FAILED) 경우만 오프라인 수납 대상으로 확정한다(이중결제 위험 없음).
                case FAILED -> ChargeOutcome.offlineRequired("RECONCILE_FAILED", target.getRetryCount());
                case PENDING -> resolveStillPending(target);
            };
        } catch (PaymentGatewayException e) {
            // 조회 자체가 실패하면 승인 여부를 알 수 없으므로 확정하지 않고 PENDING을 유지한다(다음 배치 재시도).
            return ChargeOutcome.pending(target.getRetryCount(), "RECONCILE_QUERY_FAILED");
        }
    }

    private ChargeOutcome resolveStillPending(Payment target) {
        // 재조회를 소진하도록 오래 미확정이어도 승인 여부가 불확실하면 OFFLINE(이중결제 위험) 대신 PENDING을 유지한다
        // (#34 리뷰 교정과 동일 원칙). max-attempts는 자동 OFFLINE 전환이 아니라 재시도 상한·운영 알림 임계로만 쓴다 —
        // 초과분은 RECONCILE_STUCK 사유로 표시해 운영자가 PortOne에서 직접 확인·수납하도록 남긴다(재시도 수는 더 올리지 않음).
        if (target.getRetryCount() >= properties.getMaxAttempts()) {
            return ChargeOutcome.pending(target.getRetryCount(), RECONCILE_STUCK);
        }
        return ChargeOutcome.pending(target.getRetryCount() + 1, "RECONCILE_UNCONFIRMED");
    }

    private void publishResolved(Payment finalized) {
        Long guardianMemberId = reservationLookupPort.findForCharge(finalized.getReservationId())
                .map(ReservationChargeView::guardianMemberId)
                .orElse(null);
        if (guardianMemberId == null) {
            return;
        }
        try {
            notificationPublisher.publishChargeResult(
                    guardianMemberId, finalized.getReservationId(), finalized.getId(),
                    finalized.getStatus(), finalized.getAmount());
        } catch (RuntimeException e) {
            log.warn("결제 정산 알림 발행 실패(정산은 확정됨): paymentId={}", finalized.getId(), e);
        }
    }

    // 오래 미확정으로 STUCK 확정된 결제에 "결제 확인 중"을 안내한다(결제 고도화 3.6). 발행 결정과 저장을 한 트랜잭션에서
    // 결제 행 락(findByIdForUpdate) 아래 처리해 "현재도 PENDING인지 확인"과 "안내 저장" 사이의 경합을 없앤다(PR #139
    // 리뷰 P1) — 그 사이 다른 경로가 PAID/OFFLINE로 확정했다면 락을 잡고 다시 읽었을 때 PENDING이 아니므로 발행하지
    // 않고, 완료를 확정하는 조건부 UPDATE(WHERE id=..., PENDING→...)는 같은 행 락에 직렬화되어 완료 알림 뒤에 뒤늦은
    // 안내가 저장되지 않는다. 반복 사이클·동시 발행의 삭제 전 결제당 1건은 dedup_key UNIQUE가 보장한다. 전체 삭제 후
    // 결제가 여전히 PENDING이면 안내는 다음 주기에 1회 재발행될 수 있다. 발행 실패는 격리한다 —
    // 정산 상태는 이미 확정(유지)됐다. public+@Transactional은 self 프록시 호출로 트랜잭션 경계를 적용하기 위함이다.
    @Transactional
    public void publishStuckNoticeIfPending(Long paymentId) {
        Payment locked = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (locked == null || locked.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        Long guardianMemberId = reservationLookupPort.findForCharge(locked.getReservationId())
                .map(ReservationChargeView::guardianMemberId)
                .orElse(null);
        if (guardianMemberId == null) {
            return;
        }
        try {
            notificationPublisher.publishPendingNotice(
                    guardianMemberId, locked.getReservationId(), locked.getId(), locked.getAmount());
        } catch (RuntimeException e) {
            log.warn("결제 확인 중 안내 발행 실패(정산 상태는 유지): paymentId={}", locked.getId(), e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
