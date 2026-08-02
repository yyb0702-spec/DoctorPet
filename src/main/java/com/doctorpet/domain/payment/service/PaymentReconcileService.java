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
  다중 인스턴스 동시 실행은 ReconcileLock(Redis)으로 막고, 같은 건 이중 확정은 finalizeOutcome의 PENDING 가드가 최종 방어한다.
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
            LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMillis(staleAfterMs));
            List<Payment> targets = paymentRepository.findReconcileTargets(
                    PaymentStatus.PENDING, threshold, PageRequest.of(0, batchSize));

            int paid = 0;
            int offlineRequired = 0;
            int stillPending = 0;
            int errored = 0;
            for (Payment target : targets) {
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
                        ? ChargeOutcome.paid(query.pgPaymentId(), LocalDateTime.now())
                        : ChargeOutcome.offlineRequired("RECONCILE_AMOUNT_MISMATCH", target.getRetryCount());
                case FAILED -> ChargeOutcome.offlineRequired("RECONCILE_FAILED", target.getRetryCount());
                case PENDING -> resolveStillPending(target);
            };
        } catch (PaymentGatewayException e) {
            // 조회 자체가 실패하면 승인 여부를 알 수 없으므로 확정하지 않고 PENDING을 유지한다(다음 배치 재시도).
            return ChargeOutcome.pending(target.getRetryCount(), "RECONCILE_QUERY_FAILED");
        }
    }

    private ChargeOutcome resolveStillPending(Payment target) {
        int nextAttempt = target.getRetryCount() + 1;
        if (nextAttempt >= maxAttempts) {
            // 재조회를 소진하도록 오래 미확정이면 오프라인 수납 대상으로 돌려 사람이 처리하게 한다.
            return ChargeOutcome.offlineRequired("RECONCILE_EXHAUSTED", nextAttempt);
        }
        return ChargeOutcome.pending(nextAttempt, "RECONCILE_UNCONFIRMED");
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
