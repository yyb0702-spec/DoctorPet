package com.doctorpet.domain.payment.webhook;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentWebhook;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.repository.PaymentWebhookRepository;
import com.doctorpet.domain.payment.service.PaymentReconcileService;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/*
  결제 웹훅 처리(#48). 서명 검증은 컨트롤러가 끝낸 뒤 (event_type, merchantPaymentId)만 넘어온다.
  멱등: (payment_id, event_type)로 중복 수신을 1회만 반영한다. 상태 전이는 웹훅 body를 신뢰하지 않고
  재조회(PortOne)로 확정한다(웹훅=트리거) — PaymentReconcileService.reconcilePayment 재사용. 트랜잭션을 열지
  않는다: 외부 재조회는 트랜잭션 밖에서 하고, 상태 확정은 finalizeOutcome(짧은 트랜잭션·조건부 UPDATE)이 맡는다.
  알 수 없는 결제(merchantPaymentId 미매칭)나 결제와 무관한 이벤트(paymentId 없음)는 상태를 바꾸지 않고 무시한다.
 */
@Slf4j
@Service
public class PaymentWebhookService {

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

    public void handle(String eventType, String merchantPaymentId) {
        if (isBlank(eventType) || isBlank(merchantPaymentId)) {
            log.info("결제와 무관하거나 식별 불가한 웹훅 — 무시: eventType={}, merchantPaymentId={}",
                    eventType, merchantPaymentId);
            return;
        }
        Payment payment = paymentRepository.findByMerchantPaymentId(merchantPaymentId).orElse(null);
        if (payment == null) {
            log.warn("알 수 없는 결제 웹훅 — 상태 변경 없이 무시: merchantPaymentId={}, eventType={}",
                    merchantPaymentId, eventType);
            return;
        }
        // 멱등 사전 체크(정상 경로). 경쟁 상태는 아래 UNIQUE 위반으로 최종 방어한다.
        if (webhookRepository.existsByPaymentIdAndEventType(payment.getId(), eventType)) {
            log.info("중복 결제 웹훅 — 이미 처리됨: paymentId={}, eventType={}", payment.getId(), eventType);
            return;
        }
        try {
            webhookRepository.saveAndFlush(
                    PaymentWebhook.received(payment.getId(), eventType, LocalDateTime.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // 동시 중복 수신이 UNIQUE(payment_id, event_type)에 걸린 경우 → 1회만 반영(멱등).
            log.info("동시 중복 결제 웹훅 — 1회만 반영: paymentId={}, eventType={}", payment.getId(), eventType);
            return;
        }
        // 웹훅은 트리거일 뿐 — 실제 상태는 재조회로 확정(멱등). 이미 확정된 결제면 조건부 UPDATE 0건으로 no-op.
        reconcileService.reconcilePayment(payment);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
