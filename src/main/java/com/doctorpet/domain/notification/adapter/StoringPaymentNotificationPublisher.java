package com.doctorpet.domain.notification.adapter;

// 결제 알림 발행 포트(payment #34의 PaymentNotificationPublisher)의 저장형 구현(#39).
// notifications 테이블이 생겨 로그 스텁(LoggingPaymentNotificationPublisher)을 대체한다.
// 이 빈이 PaymentNotificationPublisher 타입으로 등록되면 PaymentNotificationConfig의
// @ConditionalOnMissingBean이 로그 스텁 등록을 건너뛰어 결제 흐름 코드 변경 없이 대체된다(SA §9-8).

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoringPaymentNotificationPublisher implements PaymentNotificationPublisher {

    private final NotificationService notificationService;

    @Override
    public void publishChargeResult(
            Long guardianMemberId,
            Long reservationId,
            Long paymentId,
            PaymentStatus status
    ) {
        String content = resolveContent(status);
        if (content == null) {
            // PENDING 등 아직 확정되지 않은 결과는 보호자에게 알릴 내용이 없으므로 저장하지 않는다.
            // 정산 스케줄러(#35)가 이후 상태를 확정하면 그때 발행된다.
            return;
        }

        notificationService.create(
                guardianMemberId,
                NotificationType.PAYMENT_RESULT,
                content,
                NotificationResourceType.PAYMENT,
                paymentId
        );
    }

    private String resolveContent(PaymentStatus status) {
        return switch (status) {
            case PAID -> "진료비 결제가 완료되었습니다.";
            case OFFLINE_REQUIRED -> "진료비 자동 결제에 실패했습니다. 병원에서 현장 수납이 필요합니다.";
            case OFFLINE_PAID -> "병원 현장 수납으로 진료비 결제가 완료되었습니다.";
            case PENDING -> null;
        };
    }
}
