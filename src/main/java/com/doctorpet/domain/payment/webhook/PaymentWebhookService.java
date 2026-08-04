package com.doctorpet.domain.payment.webhook;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentWebhook;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.repository.PaymentWebhookRepository;
import com.doctorpet.domain.payment.service.PaymentReconcileService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/*
  결제 웹훅 처리(#48). 서명 검증은 컨트롤러가 끝낸 뒤 (webhook_id, event_type, merchantPaymentId)가 넘어온다.
  멱등키는 PortOne이 부여한 webhook_id다 — 같은 이벤트의 재전송은 흡수하고, event_type이 같아도 서로 다른
  이벤트는 각각 처리한다(PR #96 리뷰 반영). 상태 전이는 웹훅 body를 신뢰하지 않고 재조회(PortOne)로 확정한다
  (웹훅=트리거) — PaymentReconcileService.reconcilePayment 재사용. 트랜잭션을 열지 않는다: 외부 재조회는
  트랜잭션 밖에서 하고, 상태 확정은 finalizeOutcome(짧은 트랜잭션·조건부 UPDATE)이 맡는다.

  처리 흐름: 수신 기록(processed_at=null) → (지원 이벤트면) 재조회 → processed_at 확정. 재조회가 실패하면
  processed_at이 null로 남아, PortOne 재전송(같은 webhook_id) 때 재구동한다 — 조정 실패한 웹훅이 재시도에서
  영구 유실되던 문제를 막는다(PR #96 리뷰 반영). 재조회는 멱등(조건부 UPDATE)이라 재구동이 안전하다.

  알 수 없는 결제(merchantPaymentId 미매칭)나 결제와 무관한 이벤트(paymentId 없음)는 상태를 바꾸지 않고 무시한다.
  지원하지 않는 event_type은 감사 기록만 남기고 재조회하지 않는다 — 결제와 무관한 이벤트에 우연히 paymentId가
  실려도 상태 전이가 일어나지 않게 한다(#48 완료 조건, PR #96 리뷰 반영).
 */
@Slf4j
@Service
public class PaymentWebhookService {

    // 재조회(상태 확정)를 트리거하는 결제 이벤트. 그 외(BillingKey.*, Transaction.Ready·PayPending 등)는
    // 감사 기록만 남긴다. PortOne V2 결제 종료·취소 계열만 명시적으로 포함한다(웹훅=트리거, 실제 상태는 재조회 확정).
    private static final Set<String> RECONCILABLE_EVENT_TYPES = Set.of(
            "Transaction.Paid",
            "Transaction.Failed",
            "Transaction.Cancelled",
            "Transaction.PartialCancelled");

    private final PaymentRepository paymentRepository;
    private final PaymentWebhookRepository webhookRepository;
    private final PaymentReconcileService reconcileService;
    private final Clock clock;

    public PaymentWebhookService(
            PaymentRepository paymentRepository,
            PaymentWebhookRepository webhookRepository,
            PaymentReconcileService reconcileService,
            Clock clock
    ) {
        this.paymentRepository = paymentRepository;
        this.webhookRepository = webhookRepository;
        this.reconcileService = reconcileService;
        this.clock = clock;
    }

    public void handle(String webhookId, String eventType, String merchantPaymentId) {
        if (isBlank(eventType) || isBlank(merchantPaymentId)) {
            log.info("결제와 무관하거나 식별 불가한 웹훅 — 무시: eventType={}, merchantPaymentId={}",
                    eventType, merchantPaymentId);
            return;
        }
        if (isBlank(webhookId)) {
            // 서명 검증을 통과했다면 webhook-id는 항상 있다(Standard Webhooks 필수 헤더). 방어적으로만 무시한다.
            log.warn("webhook-id 없는 결제 웹훅 — 멱등 보장 불가로 무시: eventType={}, merchantPaymentId={}",
                    eventType, merchantPaymentId);
            return;
        }
        Payment payment = paymentRepository.findByMerchantPaymentId(merchantPaymentId).orElse(null);
        if (payment == null) {
            log.warn("알 수 없는 결제 웹훅 — 상태 변경 없이 무시: merchantPaymentId={}, eventType={}",
                    merchantPaymentId, eventType);
            return;
        }
        // 멱등 사전 체크(정상 경로). 같은 webhook_id 재전송이면 이미 처리됐는지로 재구동 여부를 가른다.
        PaymentWebhook existing = webhookRepository.findByWebhookId(webhookId).orElse(null);
        if (existing != null) {
            if (existing.isProcessed()) {
                log.info("이미 처리된 결제 웹훅 재수신 — 무시: webhookId={}, paymentId={}", webhookId, payment.getId());
                return;
            }
            // 앞선 수신에서 기록만 되고 재조회 전에 실패한 건 → 재구동(재조회는 멱등).
            log.info("미처리 결제 웹훅 재수신 — 재구동: webhookId={}, paymentId={}", webhookId, payment.getId());
            drive(existing, payment);
            return;
        }
        PaymentWebhook received =
                PaymentWebhook.received(webhookId, payment.getId(), eventType, LocalDateTime.now(clock));
        try {
            webhookRepository.saveAndFlush(received);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(webhook_id) 위반만 '동시 중복 수신'으로 흡수한다(멱등). NOT NULL·FK·길이 초과 등 다른
            // 무결성 오류를 중복으로 숨기면 안 되므로, 중복키가 아니면 다시 던진다(PR #96 리뷰 반영).
            if (!isUniqueViolation(e)) {
                throw e;
            }
            // 경쟁으로 다른 스레드가 방금 같은 webhook_id를 기록했다 — 그 스레드가 처리하도록 두고 무시한다.
            log.info("동시 중복 결제 웹훅 — 1회만 반영: webhookId={}, paymentId={}", webhookId, payment.getId());
            return;
        }
        drive(received, payment);
    }

    /**
     * 수신 기록을 실제로 구동한다: 지원 이벤트면 재조회로 상태를 확정하고, 그 외는 감사 기록만 남긴다. 어느
     * 경우든 완료되면 processed_at을 찍어 이후 같은 webhook_id 재전송이 무시되게 한다. 재조회가 예외로 실패하면
     * processed_at을 찍지 않아 재전송 때 재구동된다.
     */
    private void drive(PaymentWebhook webhook, Payment payment) {
        if (RECONCILABLE_EVENT_TYPES.contains(webhook.getEventType())) {
            // 웹훅은 트리거일 뿐 — 실제 상태는 재조회로 확정(멱등). 이미 확정된 결제면 조건부 UPDATE 0건으로 no-op.
            reconcileService.reconcilePayment(payment);
        } else {
            // 지원하지 않는 이벤트는 감사 기록만 남기고 상태를 바꾸지 않는다 — 결제와 무관한 이벤트가 상태 전이를
            // 유발하지 못하게 한다.
            log.info("지원하지 않는 결제 웹훅 이벤트 — 기록만 남기고 재조회 생략: paymentId={}, eventType={}",
                    payment.getId(), webhook.getEventType());
        }
        webhook.markProcessed(LocalDateTime.now(clock));
        webhookRepository.saveAndFlush(webhook);
    }

    /**
     * DataIntegrityViolationException이 실제 UNIQUE(중복키) 위반인지 판별한다. 중복키만 멱등으로 흡수하고
     * 그 외 제약 위반은 상위로 전파해야 한다. 중복키 SQLState는 H2·PostgreSQL은 {@code 23505}, MySQL은
     * {@code 23000}(무결성 위반 공통) + vendor 코드 {@code 1062}(Duplicate entry)로 식별한다.
     */
    private boolean isUniqueViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof SQLException sql) {
            String sqlState = sql.getSQLState();
            return "23505".equals(sqlState)
                    || ("23000".equals(sqlState) && sql.getErrorCode() == 1062);
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
