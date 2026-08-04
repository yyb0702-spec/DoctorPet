package com.doctorpet.domain.notification.adapter;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoringPaymentNotificationPublisherTest {

    private static final Long GUARDIAN_ID = 7L;
    private static final Long RESERVATION_ID = 33L;
    private static final Long PAYMENT_ID = 55L;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private StoringPaymentNotificationPublisher publisher;

    @Test
    @DisplayName("PAID: 보호자에게 PAYMENT_RESULT 알림을 결제 리소스 연결로 저장한다")
    void publish_paid_savesNotification() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.PAID);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.contains("결제가 완료"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("OFFLINE_REQUIRED: 현장 수납 안내 알림을 저장한다")
    void publish_offlineRequired_savesNotification() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.OFFLINE_REQUIRED);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.contains("현장 수납"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("OFFLINE_PAID: 현장 수납 완료 알림을 저장한다")
    void publish_offlinePaid_savesNotification() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.OFFLINE_PAID);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.anyString(),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("PENDING: 아직 확정되지 않은 결과이므로 알림을 저장하지 않는다")
    void publish_pending_doesNotSave() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.PENDING);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("paymentId가 null이어도 저장은 진행되고 resource_id만 null로 남는다")
    void publish_nullPaymentId_stillSaves() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, null, PaymentStatus.PAID);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.anyString(),
                eq(NotificationResourceType.PAYMENT),
                ArgumentMatchers.isNull()
        );
    }
}
