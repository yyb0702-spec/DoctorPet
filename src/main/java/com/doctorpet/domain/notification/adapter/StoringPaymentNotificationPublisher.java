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
            // 환불(#37)도 결제 결과 알림으로 발행한다 — NotificationType을 늘리지 않고 PAYMENT_RESULT를 재사용하며
            // (임의 확장 금지), 보호자는 resourceType=PAYMENT + 내용으로 어떤 결제의 환불인지 알 수 있다.
            // 환불 사유는 병원 내부 감사값이라 보호자 알림에 담지 않는다.
            case REFUNDED -> "진료비가 전액 환불되었습니다. 환불 처리는 카드사에 따라 영업일이 소요될 수 있습니다.";
            case PENDING -> null;
        };
    }
}
