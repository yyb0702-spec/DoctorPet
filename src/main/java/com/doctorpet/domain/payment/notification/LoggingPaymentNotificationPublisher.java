package com.doctorpet.domain.payment.notification;

import com.doctorpet.domain.payment.entity.PaymentStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * 기본 알림 발행 구현. notifications 저장소(#39)가 아직 없어 실제 저장 없이 로그만 남긴다(#34 범위).
 *
 * <p>{@link PaymentNotificationConfig}에서 {@code @ConditionalOnMissingBean}으로 등록돼, #39가 실제 저장
 * 구현을 {@link PaymentNotificationPublisher} 빈으로 제공하면 자동으로 대체된다. 민감 결제정보(빌링키·카드번호)는
 * 로그에 남기지 않는다.
 */
@Slf4j
public class LoggingPaymentNotificationPublisher implements PaymentNotificationPublisher {

    @Override
    public void publishChargeResult(Long guardianMemberId, Long reservationId, Long paymentId, PaymentStatus status) {
        log.info("결제 알림 발행(저장 미구현, #39): memberId={}, reservationId={}, paymentId={}, status={}",
                guardianMemberId, reservationId, paymentId, status);
    }
}
