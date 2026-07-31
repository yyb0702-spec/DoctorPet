package com.doctorpet.domain.payment.service;

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
    - RETRIABLE(네트워크·일시장애) → 조회 우선 후 최대 maxRetry회 재시도 → 소진 시 OFFLINE_REQUIRED
  게이트웨이 예외는 모두 여기서 상태(OFFLINE_REQUIRED/PENDING)로 흡수돼 500으로 새지 않는다(#38 리뷰 반영).
 */
@Slf4j
@Service
public class PaymentApplicationService {

    private final PaymentChargeService paymentChargeService;
    private final PaymentGateway paymentGateway;
    private final BillingKeyCryptor billingKeyCryptor;
    private final PaymentNotificationPublisher notificationPublisher;
    private final RetryBackoff retryBackoff;
    private final int maxRetry;

    public PaymentApplicationService(
            PaymentChargeService paymentChargeService,
            PaymentGateway paymentGateway,
            BillingKeyCryptor billingKeyCryptor,
            PaymentNotificationPublisher notificationPublisher,
            RetryBackoff retryBackoff,
            // 재시도 유효 실패의 최대 재시도 횟수(SA §9-4 확정 = 3).
            @Value("${payment.charge.max-retry:3}") int maxRetry
    ) {
        this.paymentChargeService = paymentChargeService;
        this.paymentGateway = paymentGateway;
        this.billingKeyCryptor = billingKeyCryptor;
        this.notificationPublisher = notificationPublisher;
        this.retryBackoff = retryBackoff;
        this.maxRetry = maxRetry;
    }

    /**
     * 진료비 청구. {@code staffMemberId}는 인증 주체(스태프)이며 자병원 검증에 쓴다. 결제수단·보호자는 예약에서 얻는다.
     */
    public PaymentChargeResponse charge(Long reservationId, Long staffMemberId, int amount) {
        // Tx1 — 검증 + PENDING 선기록(커밋).
        PaymentPreRecord pre = paymentChargeService.preRecord(reservationId, staffMemberId, amount);

        // 외부 승인(트랜잭션 밖). 결제수단이 ACTIVE가 아니면 게이트웨이 호출 없이 즉시 오프라인 확정(SA §9-4·§4-2).
        ChargeOutcome outcome = pre.paymentMethodActive()
                ? attemptBillingKeyCharge(pre)
                : ChargeOutcome.offlineRequired("PAYMENT_METHOD_INACTIVE", 0);

        // Tx2 — 상태 확정(커밋).
        Payment payment = paymentChargeService.finalizeOutcome(pre.paymentId(), outcome);
        PaymentChargeResponse response = PaymentChargeResponse.from(payment);

        // 커밋 이후 알림. 발행 실패가 결제 확정을 되돌리지 않도록 트랜잭션 밖에서 호출하고, 예외도 삼킨다(SA §9-8).
        // 결제는 이미 커밋됐으므로 알림 발행이 실패해도 청구 응답까지 500으로 만들지 않는다.
        try {
            notificationPublisher.publishChargeResult(
                    pre.guardianMemberId(), reservationId, payment.getId(), payment.getStatus());
        } catch (RuntimeException e) {
            log.warn("결제 알림 발행 실패(결제는 확정됨): paymentId={}, status={}", payment.getId(), payment.getStatus(), e);
        }
        return response;
    }

    // --- 외부 승인·실패 분기 (트랜잭션 밖) ---

    private ChargeOutcome attemptBillingKeyCharge(PaymentPreRecord pre) {
        // 청구 시점에만 원문 빌링키를 복호화한다(로그·저장 금지). 재시도에 재사용하려 지역 변수로만 보관한다.
        String billingKey = billingKeyCryptor.decrypt(pre.billingKeyEnc());
        try {
            PaymentApproveResult result = paymentGateway.approve(buildCommand(pre, billingKey));
            return resolveApproveResult(pre, result, 0);
        } catch (PaymentGatewayException e) {
            return branchOnFailure(pre, billingKey, e);
        }
    }

    private ChargeOutcome resolveApproveResult(PaymentPreRecord pre, PaymentApproveResult result, int retryCount) {
        return switch (result.status()) {
            case PAID -> paid(result.pgPaymentId(), result.approvedAt());
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
                case PAID -> query.paidAmount() == pre.amount()
                        ? paid(query.pgPaymentId(), null)
                        : ChargeOutcome.offlineRequired("AMOUNT_MISMATCH", retryCount);
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
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            retryBackoff.pause(attempt);

            // 조회 우선 — 앞선 승인이 실제로는 처리됐을 수 있다.
            try {
                PaymentQueryResult query = paymentGateway.query(pre.merchantPaymentId());
                if (query.status() == GatewayPaymentStatus.PAID) {
                    return query.paidAmount() == pre.amount()
                            ? paid(query.pgPaymentId(), null)
                            : ChargeOutcome.offlineRequired("AMOUNT_MISMATCH", attempt);
                }
            } catch (PaymentGatewayException ignored) {
                // 조회 실패는 무시하고 재승인으로 진행한다.
            }

            try {
                PaymentApproveResult result = paymentGateway.approve(buildCommand(pre, billingKey));
                if (result.status() == GatewayPaymentStatus.PAID) {
                    return paid(result.pgPaymentId(), result.approvedAt());
                }
                // PENDING(미확정)이면 다음 사이클에서 다시 확인한다.
            } catch (PaymentGatewayException e) {
                if (e.getFailureReason() == GatewayFailureReason.NON_RETRIABLE) {
                    return ChargeOutcome.offlineRequired("NON_RETRIABLE", attempt);
                }
                // RETRIABLE/UNKNOWN → 다음 사이클로 계속.
            }
        }
        return ChargeOutcome.offlineRequired("RETRY_EXHAUSTED", maxRetry);
    }

    private ChargeOutcome paid(String pgPaymentId, LocalDateTime approvedAt) {
        return ChargeOutcome.paid(pgPaymentId, approvedAt != null ? approvedAt : LocalDateTime.now());
    }

    private PaymentApproveCommand buildCommand(PaymentPreRecord pre, String billingKey) {
        // orderName은 공급자 표기용이라 민감정보를 담지 않는다.
        String orderName = "DoctorPet 진료비 (예약 " + pre.reservationId() + ")";
        return new PaymentApproveCommand(pre.merchantPaymentId(), billingKey, pre.amount(), orderName);
    }
}
