package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.audit.PaymentChargeAuditLogger;
import com.doctorpet.domain.payment.config.PaymentChargeProperties;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
  진료비 청구 오케스트레이션(#34, SA §9-4). 여러 단계(선기록·외부 승인·후확정·알림)를 조합하므로 ApplicationService로 둔다.
  트랜잭션 경계는 PaymentChargeService(Tx1/Tx2)에 있고, 이 클래스 자체는 트랜잭션을 열지 않는다 —
  PortOne 승인·조회·재시도(외부 호출)를 트랜잭션 밖에서 수행하기 위함이다(가드레일·SA §9-4).

  실패 분기(SA §9-4):
    - NON_RETRIABLE(한도·정지·만료·삭제된 결제수단) → 즉시 OFFLINE_REQUIRED
    - UNKNOWN(타임아웃) → 재시도 금지, 단건조회 먼저 → 미확정이면 PENDING 유지(정산 스케줄러 #35가 확정)
    - RETRIABLE(네트워크·일시장애) → 조회 우선 후 최대 maxRetry회 재시도 → 소진 시 최종 단건조회로 확정
                                      (PAID/성공아님 확인은 확정, 미확정이면 PENDING 유지 — 오프라인 이중수납 금지)
  게이트웨이 예외는 모두 여기서 상태(OFFLINE_REQUIRED/PENDING)로 흡수돼 500으로 새지 않는다(#38 리뷰 반영).
  게이트웨이 계약을 벗어난 예외(복호화 실패·어댑터 버그 등)도 resolveOutcomeSafely가 최종 흡수한다 — 복호화 실패는
  청구 미발생이 확실해 OFFLINE_REQUIRED, 그 외 오케스트레이션 예외는 승인 도달 불명이라 PENDING으로 흡수한다(PR #77 3차 검증 반영).

  단, PG가 PAID를 반환했으나 승인 금액 불일치·pgPaymentId 누락 같은 정합성 오류는 "자동결제 실패"가 아니다 —
  이미 승인돼 돈이 이동했을 수 있으므로 OFFLINE_REQUIRED(현장 수납 허용)로 돌리면 이중결제가 된다.
  이 경우 사유를 기록한 채 PENDING을 유지해 오프라인 정산을 막고, #35 재조회·운영 확인으로 확정한다(PRD §6-7, PR #77 2차 리뷰 반영).
 */
@Slf4j
@Service
public class PaymentApplicationService {

    private final PaymentChargeService paymentChargeService;
    private final PaymentGateway paymentGateway;
    private final BillingKeyCryptor billingKeyCryptor;
    private final PaymentNotificationPublisher notificationPublisher;
    private final PaymentChargeAuditLogger chargeAuditLogger;
    private final RetryBackoff retryBackoff;
    private final int maxRetry;
    // 재시도 백오프 누적 대기의 상한(ms, #85 데드라인 캡). HTTP 요청 스레드가 지수 백오프로 과도하게 점유돼
    // 스레드풀이 고갈되는 것을 막는다. 기본값은 현재 기본 백오프 3회 누적(500+1000+2000)과 같은 3500ms라
    // 기본 설정의 동작을 바꾸지 않는다 — backoff-initial-ms·max-retry를 상향하면 이 값도 함께 올려야 한다.
    private final long retryBackoffDeadlineMs;

    public PaymentApplicationService(
            PaymentChargeService paymentChargeService,
            PaymentGateway paymentGateway,
            BillingKeyCryptor billingKeyCryptor,
            PaymentNotificationPublisher notificationPublisher,
            PaymentChargeAuditLogger chargeAuditLogger,
            RetryBackoff retryBackoff,
            // 재시도 유효 실패의 최대 재시도 횟수(SA §9-4 확정 = 3).
            @Value("${payment.charge.max-retry:3}") int maxRetry,
            // 데드라인 캡은 시작 시점 양수 검증을 위해 @ConfigurationProperties로 받는다(PR #92 P2).
            PaymentChargeProperties chargeProperties
    ) {
        this.paymentChargeService = paymentChargeService;
        this.paymentGateway = paymentGateway;
        this.billingKeyCryptor = billingKeyCryptor;
        this.notificationPublisher = notificationPublisher;
        this.chargeAuditLogger = chargeAuditLogger;
        this.retryBackoff = retryBackoff;
        this.maxRetry = maxRetry;
        this.retryBackoffDeadlineMs = chargeProperties.getRetryBackoffDeadlineMs();
    }

    /**
     * 진료비 청구. {@code staffMemberId}는 인증 주체(스태프)이며 자병원 검증에 쓴다. 결제수단·보호자는 예약에서 얻는다.
     */
    public PaymentChargeResponse charge(Long reservationId, Long staffMemberId, int amount) {
        // Tx1 — 검증 + PENDING 선기록(커밋).
        PaymentPreRecord pre = paymentChargeService.preRecord(reservationId, staffMemberId, amount);

        // 청구 접수 감사 기록(#84). Tx1 커밋 직후, 외부 승인 결과와 무관하게 "누가·얼마·어느 예약"을 남긴다 —
        // 과다청구를 코드로 막지는 않지만(후불 최종액 입력은 설계 의도) 사후 추적이 가능하게 한다.
        chargeAuditLogger.recordChargeAccepted(staffMemberId, reservationId, pre.paymentId(), pre.amount());

        // 외부 승인(트랜잭션 밖). 결제수단이 ACTIVE가 아니면 게이트웨이 호출 없이 즉시 오프라인 확정(SA §9-4·§4-2).
        ChargeOutcome outcome = resolveOutcomeSafely(pre);

        // Tx2 — 상태 확정(커밋). 조건부 전이라 정산 스케줄러(#35)가 먼저 확정한 경우 이 호출은 덮어쓰지 않는다.
        PaymentChargeService.FinalizeResult result = paymentChargeService.finalizeOutcome(pre.paymentId(), outcome);
        Payment payment = result.payment();
        PaymentChargeResponse response = PaymentChargeResponse.from(payment);

        // 커밋 이후 알림. 발행 실패가 결제 확정을 되돌리지 않도록 트랜잭션 밖에서 호출하고, 예외도 삼킨다(SA §9-8).
        // 결제는 이미 커밋됐으므로 알림 발행이 실패해도 청구 응답까지 500으로 만들지 않는다.
        // 이 호출이 실제로 상태를 전이시켰을 때만 발행한다 — 정산 경로가 먼저 확정했다면 그쪽이 이미 발행했으므로 중복 발행을 막는다.
        if (result.applied()) {
            try {
                notificationPublisher.publishChargeResult(
                        pre.guardianMemberId(), reservationId, payment.getId(), payment.getStatus());
            } catch (RuntimeException e) {
                log.warn("결제 알림 발행 실패(결제는 확정됨): paymentId={}, status={}", payment.getId(), payment.getStatus(), e);
            }
        }
        return response;
    }

    // --- 외부 승인·실패 분기 (트랜잭션 밖) ---

    /**
     * 외부 승인 오케스트레이션을 상태(ChargeOutcome)로 감싸 어떤 예외도 500으로 새지 않게 한다(SA §9-4 핵심 불변식).
     * 게이트웨이 실패는 branchOnFailure/resolveByQuery가 이미 상태로 흡수하지만, 게이트웨이 계약을 벗어난
     * RuntimeException(어댑터 버그 등)이 재시도·조회 어느 지점에서 새어 나와도 여기서 최종 흡수한다.
     * 이때 승인 도달 여부가 불명이라 OFFLINE_REQUIRED(현장 수납)로 보내면 이중결제 위험 → PENDING 유지(#35가 확정).
     * (복호화 실패는 게이트웨이 호출 전이라 attemptBillingKeyCharge에서 OFFLINE_REQUIRED로 별도 확정한다.)
     */
    private ChargeOutcome resolveOutcomeSafely(PaymentPreRecord pre) {
        if (!pre.paymentMethodActive()) {
            return ChargeOutcome.offlineRequired("PAYMENT_METHOD_INACTIVE", 0);
        }
        try {
            return attemptBillingKeyCharge(pre);
        } catch (RuntimeException e) {
            log.error("결제 외부 승인 오케스트레이션에서 계약 외 예외 — 500 차단, PENDING 유지: paymentId={}",
                    pre.paymentId(), e);
            return ChargeOutcome.pending(0, "CHARGE_ORCHESTRATION_ERROR");
        }
    }

    private ChargeOutcome attemptBillingKeyCharge(PaymentPreRecord pre) {
        // 청구 시점에만 원문 빌링키를 복호화한다(로그·저장 금지). 재시도에 재사용하려 지역 변수로만 보관한다.
        String billingKey;
        try {
            billingKey = billingKeyCryptor.decrypt(pre.billingKeyEnc());
        } catch (RuntimeException e) {
            // 복호화 실패는 게이트웨이 호출 전이라 청구가 절대 일어나지 않았음이 확실 → 오프라인 수납으로 안전 확정
            // (PENDING과 달리 현장에서 즉시 다른 수단으로 수납 가능, 이중결제 위험 없음).
            log.warn("빌링키 복호화 실패 — 게이트웨이 호출 전이라 청구 미발생, 오프라인 확정: paymentId={}", pre.paymentId(), e);
            return ChargeOutcome.offlineRequired("BILLING_KEY_DECRYPT_FAILED", 0);
        }
        try {
            PaymentApproveResult result = paymentGateway.approve(buildCommand(pre, billingKey));
            return resolveApproveResult(pre, result, 0);
        } catch (PaymentGatewayException e) {
            return branchOnFailure(pre, billingKey, e);
        }
    }

    private ChargeOutcome resolveApproveResult(PaymentPreRecord pre, PaymentApproveResult result, int retryCount) {
        return switch (result.status()) {
            case PAID -> confirmPaid(pre, result.pgPaymentId(), result.approvedAmount(), result.approvedAt(), retryCount);
            // 승인 응답이 미확정(PENDING)으로 오면 단건조회로 재확정한다.
            case PENDING -> resolveByQuery(pre, retryCount);
            // 계약상 실패는 예외로 오지만, 방어적으로 오프라인 확정 처리한다.
            case FAILED -> ChargeOutcome.offlineRequired("GATEWAY_FAILED", retryCount);
        };
    }

    private ChargeOutcome branchOnFailure(PaymentPreRecord pre, String billingKey, PaymentGatewayException e) {
        return switch (e.getFailureReason()) {
            case NON_RETRIABLE -> ChargeOutcome.offlineRequired("NON_RETRIABLE", 0);
            case UNKNOWN -> resolveByQuery(pre, 0);
            case RETRIABLE -> retryCharge(pre, billingKey);
        };
    }

    /**
     * 단건조회로 승인 여부를 재확정한다(SA §9-4 — 타임아웃 시 무조건 재시도 금지, 조회 우선).
     * PAID면 금액 대조 후 확정, FAILED면 오프라인, 여전히 미확정이면 PENDING 유지(#35가 이어서 확정).
     */
    private ChargeOutcome resolveByQuery(PaymentPreRecord pre, int retryCount) {
        try {
            PaymentQueryResult query = paymentGateway.query(pre.merchantPaymentId());
            return switch (query.status()) {
                case PAID -> confirmPaid(pre, query.pgPaymentId(), query.paidAmount(), null, retryCount);
                case FAILED -> ChargeOutcome.offlineRequired("CONFIRMED_FAILED", retryCount);
                case PENDING -> ChargeOutcome.pending(retryCount, "UNCONFIRMED_TIMEOUT");
            };
        } catch (PaymentGatewayException e) {
            // 조회마저 실패하면 승인 여부를 알 수 없으므로 PENDING 유지(오프라인 확정 금지 — 이미 승인됐을 수 있음).
            return ChargeOutcome.pending(retryCount, "QUERY_FAILED");
        }
    }

    /**
     * 재시도 유효 실패의 재시도 루프(SA §9-4). 매 시도 전 단건조회로 이미 처리됐는지 확인한 뒤 재승인한다.
     * NON_RETRIABLE로 바뀌면 즉시 오프라인, 소진되면 오프라인 확정한다.
     */
    private ChargeOutcome retryCharge(PaymentPreRecord pre, String billingKey) {
        // 백오프 누적 시간 예산(#85 데드라인 캡). 지수 백오프가 HTTP 요청 스레드를 무한정 동기 점유해
        // 스레드풀을 굶기지 않도록, 누적 대기가 예산을 넘으면 더 재시도하지 않고 최종 확정으로 빠진다.
        long backoffUsedMs = 0;
        // 실제로 수행한 재시도 횟수. 데드라인 예산 소진으로 조기 종료하면 maxRetry보다 작으므로, 최종 확정에
        // 무조건 maxRetry를 넣지 않고 이 값을 넘겨 payments.retry_count와 정산 임계가 실제와 맞게 한다(PR #92 P2).
        int performedRetries = 0;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            long remainingBudgetMs = retryBackoffDeadlineMs - backoffUsedMs;
            if (remainingBudgetMs <= 0) {
                // 백오프 예산 소진 — 남은 재시도를 포기하고 최종 단건조회로 안전 확정한다(미확정이면 PENDING 유지, #35가 정산).
                break;
            }
            performedRetries = attempt;
            backoffUsedMs += retryBackoff.pause(attempt, remainingBudgetMs);

            // 조회 우선 — 앞선 승인이 실제로는 처리됐을 수 있다.
            try {
                PaymentQueryResult query = paymentGateway.query(pre.merchantPaymentId());
                if (query.status() == GatewayPaymentStatus.PAID) {
                    return confirmPaid(pre, query.pgPaymentId(), query.paidAmount(), null, attempt);
                }
            } catch (PaymentGatewayException ignored) {
                // 조회 실패는 무시하고 재승인으로 진행한다.
            }

            try {
                PaymentApproveResult result = paymentGateway.approve(buildCommand(pre, billingKey));
                if (result.status() == GatewayPaymentStatus.PAID) {
                    return confirmPaid(pre, result.pgPaymentId(), result.approvedAmount(), result.approvedAt(), attempt);
                }
                // PENDING(미확정)이면 다음 사이클에서 다시 확인한다.
            } catch (PaymentGatewayException e) {
                if (e.getFailureReason() == GatewayFailureReason.NON_RETRIABLE) {
                    return ChargeOutcome.offlineRequired("NON_RETRIABLE", attempt);
                }
                // RETRIABLE/UNKNOWN → 다음 사이클로 계속.
            }
        }
        // 소진 직후 곧바로 오프라인으로 보내지 않는다 — 마지막 승인 시도가 UNKNOWN(타임아웃)이었다면 실제로는
        // 승인됐을 수 있다. 최종 단건조회로 성공/실패를 확인하고, 미확정이면 PENDING을 유지한다(§9-4 소진 분기).
        return resolveAfterExhaustion(pre, performedRetries);
    }

    /**
     * 재시도 소진 후 최종 확정(SA §9-4 — OFFLINE_REQUIRED는 "성공하지 않음이 확인된" 경우에만).
     * PAID면 금액 대조 후 확정(불일치는 confirmPaid가 PENDING 반환), FAILED(성공 아님 확인)면 오프라인,
     * 여전히 미확정(PENDING·조회 실패)이면 PENDING 유지 — 이미 승인됐을 수 있어 오프라인 이중수납을 금지하고
     * 정산 스케줄러(#35)가 확정한다.
     *
     * <p>{@code performedRetries}는 루프에서 실제로 수행한 재시도 횟수다. 데드라인 예산 소진으로 조기 종료하면
     * maxRetry보다 작으므로, 이 값을 그대로 retry_count에 기록해 감사 정보와 정산 임계가 실제와 어긋나지 않게
     * 한다(PR #92 P2). 루프를 끝까지 돌면 maxRetry와 같다.
     */
    private ChargeOutcome resolveAfterExhaustion(PaymentPreRecord pre, int performedRetries) {
        try {
            PaymentQueryResult query = paymentGateway.query(pre.merchantPaymentId());
            return switch (query.status()) {
                case PAID -> confirmPaid(pre, query.pgPaymentId(), query.paidAmount(), null, performedRetries);
                case FAILED -> ChargeOutcome.offlineRequired("RETRY_EXHAUSTED", performedRetries);
                case PENDING -> ChargeOutcome.pending(performedRetries, "RETRY_EXHAUSTED_UNCONFIRMED");
            };
        } catch (PaymentGatewayException e) {
            // 최종 조회마저 실패하면 승인 여부를 알 수 없으므로 오프라인 금지, PENDING 유지.
            return ChargeOutcome.pending(performedRetries, "RETRY_EXHAUSTED_UNCONFIRMED");
        }
    }

    /**
     * PAID 응답을 확정 전에 검증한다(SA §9-4 — 서버가 클라이언트 결과를 믿지 않고 금액·상태를 확인).
     * 승인 금액이 요청 금액과 다르거나 pgPaymentId가 비어 있으면 PAID로 확정하지 않는다. 다만 PG가 PAID를 반환한
     * 이상 이미 승인돼 돈이 이동했을 수 있으므로, 오프라인 수납 대상(OFFLINE_REQUIRED)으로 돌리면 자동결제와
     * 현장 수납이 중복된다. 따라서 정합성 오류 사유를 기록한 채 PENDING을 유지해 오프라인 정산을 막고, 정산
     * 스케줄러(#35) 재조회·운영 확인으로 확정한다(PRD §6-7·SA §9-4 이중청구 방지, PR #77 2차 리뷰 반영).
     * 최초 승인·재승인·단건조회의 모든 PAID 경로가 공유한다.
     */
    private ChargeOutcome confirmPaid(
            PaymentPreRecord pre, String pgPaymentId, int paidAmount, LocalDateTime paidAt, int retryCount) {
        if (paidAmount != pre.amount()) {
            return ChargeOutcome.pending(retryCount, "AMOUNT_MISMATCH");
        }
        if (pgPaymentId == null || pgPaymentId.isBlank()) {
            return ChargeOutcome.pending(retryCount, "INVALID_PG_RESULT");
        }
        return ChargeOutcome.paid(pgPaymentId, paidAt != null ? paidAt : LocalDateTime.now());
    }

    private PaymentApproveCommand buildCommand(PaymentPreRecord pre, String billingKey) {
        // orderName은 공급자 표기용이라 민감정보를 담지 않는다.
        String orderName = "DoctorPet 진료비 (예약 " + pre.reservationId() + ")";
        return new PaymentApproveCommand(pre.merchantPaymentId(), billingKey, pre.amount(), orderName);
    }
}
