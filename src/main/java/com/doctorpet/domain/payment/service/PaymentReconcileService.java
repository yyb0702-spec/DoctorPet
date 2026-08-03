package com.doctorpet.domain.payment.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/*
  결제 정산(reconcile) 배치(#35, SA §9-7·§9-4). 타임아웃 등으로 PENDING에 남은 결제를 주기적으로 단건조회해
  최종 상태(PAID/OFFLINE_REQUIRED)로 수렴시킨다. 이 클래스는 트랜잭션을 열지 않는다 — 외부 조회(PortOne)는 트랜잭션
  밖에서 하고, 상태 확정은 #34의 PaymentChargeService.finalizeOutcome(짧은 트랜잭션)을 재사용한다. 알림도 커밋 이후 발행한다.

  분기(건별):
  - 조회 PAID(금액·pgId 일치) → PAID 확정
  - 조회 FAILED → OFFLINE_REQUIRED 확정
  - 조회 PENDING(여전히 미확정) → 재시도 수 +1, 임계(max-attempts) 초과면 OFFLINE_REQUIRED(수동 정산 전환), 아니면 PENDING 유지
  - 조회 실패(게이트웨이 예외) → PENDING 유지(다음 배치 재시도), 재시도 수는 올리지 않음
  다중 인스턴스 동시 실행은 ReconcileLock(Redis)으로 막는다 — 배치가 길어져도 처리 중 lease를 갱신(renew)해 만료로
  다른 인스턴스가 끼어드는 것을 막고, 락 해제는 내 토큰일 때만 원자적으로 한다. finalizeOutcome의 PENDING 가드는 순차
  재확정(같은 건을 이미 확정된 뒤 다시 확정)만 막으므로, 동시 실행 차단의 1차 방어는 어디까지나 이 락이다.
 */
@Slf4j
@Service
public class PaymentReconcileService {

    private final PaymentRepository paymentRepository;
    private final PaymentChargeService paymentChargeService;
    private final PaymentGateway paymentGateway;
    private final PaymentNotificationPublisher notificationPublisher;
    private final ReservationLookupPort reservationLookupPort;
    private final ReconcileLock reconcileLock;
    // JpaAuditing의 updatedAt과 같은 서울 기준 Clock(applicationClock). 조회 임계·확정 시각을 JVM 기본 시간대가
    // 아니라 이 Clock으로 계산해, 운영·CI JVM이 UTC여도 updatedAt과 임계값의 시간대가 어긋나지 않게 한다.
    private final Clock clock;
    private final long staleAfterMs;
    private final int batchSize;
    private final int maxAttempts;

    public PaymentReconcileService(
            PaymentRepository paymentRepository,
            PaymentChargeService paymentChargeService,
            PaymentGateway paymentGateway,
            PaymentNotificationPublisher notificationPublisher,
            ReservationLookupPort reservationLookupPort,
            ReconcileLock reconcileLock,
            Clock clock,
            @Value("${payment.reconcile.stale-after-ms:120000}") long staleAfterMs,
            @Value("${payment.reconcile.batch-size:100}") int batchSize,
            @Value("${payment.reconcile.max-attempts:10}") int maxAttempts
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentChargeService = paymentChargeService;
        this.paymentGateway = paymentGateway;
        this.notificationPublisher = notificationPublisher;
        this.reservationLookupPort = reservationLookupPort;
        this.reconcileLock = reconcileLock;
        this.clock = clock;
        this.staleAfterMs = staleAfterMs;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** 정산 배치 1회 실행. 다른 인스턴스가 실행 중이면 스킵한다. */
    public ReconcileSummary reconcile() {
        Optional<String> lock = reconcileLock.tryLock();
        if (lock.isEmpty()) {
            return ReconcileSummary.skipped();
        }
        try {
            LocalDateTime threshold = LocalDateTime.now(clock).minus(Duration.ofMillis(staleAfterMs));
            List<Payment> targets = paymentRepository.findReconcileTargets(
                    PaymentStatus.PENDING, threshold, PageRequest.of(0, batchSize));

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

    private ChargeOutcome.Type reconcileOne(Payment target) {
        ChargeOutcome outcome = resolveByQuery(target);
        Payment finalized = paymentChargeService.finalizeOutcome(target.getId(), outcome);
        if (outcome.type() != ChargeOutcome.Type.PENDING) {
            publishResolved(finalized);
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
        if (target.getRetryCount() >= maxAttempts) {
            return ChargeOutcome.pending(target.getRetryCount(), "RECONCILE_STUCK");
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
                    guardianMemberId, finalized.getReservationId(), finalized.getId(), finalized.getStatus());
        } catch (RuntimeException e) {
            log.warn("결제 정산 알림 발행 실패(정산은 확정됨): paymentId={}", finalized.getId(), e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
