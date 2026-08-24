package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
  환불 오케스트레이션(#37, SA §9-4). 선점(Tx1) → PG 취소 → 확정(Tx2) → 알림을 조합한다. 이 클래스는 트랜잭션을
  열지 않는다 — PG 취소를 트랜잭션 밖에서 하기 위함이다(가드레일·청구 PaymentApplicationService와 같은 구조).

  청구와 다른 점: 환불은 재시도 루프를 돌지 않는다. 청구는 실패해도 오프라인 수납이라는 대안 경로가 있어 요청 안에서
  결론을 내야 하지만, 환불은 실패 시 결제를 PAID로 되돌려두고 스태프가 다시 요청하면 같은 멱등키로 재시도된다.
  HTTP 요청 스레드를 백오프로 붙잡아 둘 이유가 없고, 취소 실패는 스태프가 즉시 인지해야 하는 사건이다.

  게이트웨이 예외는 상태(FAILED)로 기록한 뒤 REFUND_GATEWAY_FAILED(502)로 올린다 — 청구와 달리 500으로 흡수하지
  않는다. 환불은 스태프가 명시적으로 요청한 동작이라 "실패했으니 다시 시도하라"는 사실이 응답으로 전달돼야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundService {

    private final PaymentRefundTxService paymentRefundTxService;
    private final PaymentGateway paymentGateway;
    private final PaymentNotificationPublisher notificationPublisher;

    /**
     * 진료비 전액 환불. {@code staffMemberId}는 인증 주체(스태프)이며 자병원 검증에 쓴다.
     * 보호자·예약·금액은 모두 결제 레코드와 예약에서 얻는다(요청 값 신뢰 금지, 보안).
     */
    public PaymentHistoryResponse refund(Long paymentId, Long staffMemberId, String reason) {
        // Tx1 — 검증 + 선점(커밋). 이미 환불된 건은 PG 호출 없이 멱등 응답으로 끝난다.
        RefundClaim claim = paymentRefundTxService.claim(paymentId, staffMemberId, reason);
        if (!claim.claimed()) {
            return claim.response();
        }

        // PG 취소(트랜잭션 밖). 같은 merchantRefundId 재호출은 공급자가 기존 취소 결과를 돌려준다.
        PaymentCancelResult result = cancelAtGateway(claim);

        // 취소 금액이 요청과 다르면 환불이 의도대로 성립하지 않았다. 전액 취소만 요청하므로 정상 경로에서는
        // 발생하지 않는 방어 분기다 — REFUNDED로 확정하면 실제와 다른 금액이 이력에 남으므로 실패로 기록하고
        // 운영 확인 대상으로 남긴다(결제는 PAID 유지).
        if (result.amount() != claim.amount()) {
            log.error("PG 취소 금액 불일치 — 환불 미확정, 수동 확인 필요: paymentId={}, 요청={}, 취소={}",
                    paymentId, claim.amount(), result.amount());
            paymentRefundTxService.fail(claim.refundId(), claim.claimToken(), "REFUND_AMOUNT_MISMATCH");
            throw new ServiceException(PaymentErrorCode.REFUND_GATEWAY_FAILED);
        }

        // Tx2 — 이력 COMPLETED + 결제 PAID→REFUNDED(커밋).
        RefundOutcome outcome = paymentRefundTxService.complete(
                claim.refundId(), paymentId, claim.claimToken(), result.pgCancelId());

        // 커밋 이후 알림. 발행 실패가 환불 확정을 되돌리지 않도록 트랜잭션 밖에서 호출하고 예외도 삼킨다(SA §9-8).
        // 실제로 전이시킨 요청만 발행해, 멈춘 선점을 회수한 재시도가 알림을 중복 발행하지 않게 한다.
        if (outcome.applied()) {
            publishRefunded(claim, outcome);
        }
        return outcome.response();
    }

    // 공급자에 넘기는 취소 사유는 고정 문구다. 스태프가 입력한 사유는 병원 내부 감사값이라 외부 공급자에
    // 전달하지 않는다 — 자유 입력이라 보호자 정보나 진료 내용이 섞일 수 있고, 그게 PG 콘솔·정산 내역에
    // 남으면 최소 전송 원칙에 어긋난다. 입력 사유는 payment_refunds.reason에만 보관한다(보안).
    private static final String GATEWAY_CANCEL_REASON = "진료비 환불";

    private PaymentCancelResult cancelAtGateway(RefundClaim claim) {
        PaymentCancelCommand command = new PaymentCancelCommand(
                claim.merchantPaymentId(), claim.merchantRefundId(), claim.amount(), GATEWAY_CANCEL_REASON);
        try {
            return paymentGateway.cancel(command);
        } catch (PaymentGatewayException e) {
            // 실패 성격(RETRIABLE·NON_RETRIABLE·UNKNOWN)을 사유로 기록한다. 공급자 오류 원문은 남기지 않는다(보안).
            // UNKNOWN(타임아웃 등)은 취소가 실제로 성립했을 수 있는데, 같은 멱등키 재시도가 그때 기존 취소 결과를
            // 받아 REFUNDED로 확정하므로 여기서 성공으로 단정하지 않는다.
            log.warn("PG 취소 실패 — 환불 미확정(결제는 PAID 유지): paymentId={}, refundId={}, reason={}",
                    claim.response().paymentId(), claim.refundId(), e.getFailureReason(), e);
            paymentRefundTxService.fail(claim.refundId(), claim.claimToken(), e.getFailureReason().name());
            throw new ServiceException(PaymentErrorCode.REFUND_GATEWAY_FAILED);
        } catch (RuntimeException e) {
            // 게이트웨이 계약을 벗어난 예외(어댑터 버그 등)도 선점을 풀어야 재시도가 가능하다.
            log.error("환불 오케스트레이션에서 계약 외 예외 — 선점 해제: refundId={}", claim.refundId(), e);
            paymentRefundTxService.fail(claim.refundId(), claim.claimToken(), "REFUND_ORCHESTRATION_ERROR");
            throw new ServiceException(PaymentErrorCode.REFUND_GATEWAY_FAILED);
        }
    }

    private void publishRefunded(RefundClaim claim, RefundOutcome outcome) {
        try {
            notificationPublisher.publishChargeResult(
                    claim.guardianMemberId(), claim.reservationId(),
                    outcome.response().paymentId(), outcome.response().status(), outcome.response().amount());
        } catch (RuntimeException e) {
            log.warn("환불 알림 발행 실패(환불은 확정됨): paymentId={}", outcome.response().paymentId(), e);
        }
    }
}
