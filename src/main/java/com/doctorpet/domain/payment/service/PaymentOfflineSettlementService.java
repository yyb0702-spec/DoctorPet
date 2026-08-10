package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
  오프라인 정산 오케스트레이션(#36, SA §9-4·§8-7). 트랜잭션 전이(PaymentOfflineSettleTxService)와 알림 발행을 조합한다.
  이 클래스 자체는 트랜잭션을 열지 않는다 — 정산 트랜잭션이 커밋된 뒤에 알림을 발행해, 발행 실패가 정산을 되돌리지
  않게 한다(SA §9-8). 실제 정산을 수행한 요청(freshlySettled)만 알림을 발행하며, 이미 정산돼 있던 멱등 응답은 발행하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOfflineSettlementService {

    private final PaymentOfflineSettleTxService paymentOfflineSettleTxService;
    private final PaymentNotificationPublisher notificationPublisher;

    public PaymentHistoryResponse settle(Long paymentId, Long staffMemberId) {
        OfflineSettleOutcome outcome = paymentOfflineSettleTxService.settle(paymentId, staffMemberId);
        if (outcome.freshlySettled()) {
            publishSettled(outcome);
        }
        return outcome.response();
    }

    private void publishSettled(OfflineSettleOutcome outcome) {
        PaymentHistoryResponse settled = outcome.response();
        try {
            notificationPublisher.publishChargeResult(
                    outcome.guardianMemberId(), settled.reservationId(), settled.paymentId(), settled.status(),
                    settled.amount());
        } catch (RuntimeException e) {
            // 정산은 이미 커밋됐으므로 알림 발행 실패가 응답을 500으로 만들지 않는다(SA §9-8).
            log.warn("오프라인 정산 알림 발행 실패(정산은 확정됨): paymentId={}", settled.paymentId(), e);
        }
    }
}
