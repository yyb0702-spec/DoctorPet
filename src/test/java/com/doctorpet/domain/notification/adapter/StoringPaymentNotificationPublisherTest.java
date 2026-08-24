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
    // 천단위 콤마 표기를 검증하기 위한 금액(80000 → "80,000").
    private static final int AMOUNT = 80_000;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private StoringPaymentNotificationPublisher publisher;

    @Test
    @DisplayName("PAID: 금액을 천단위 콤마로 담은 PAYMENT_RESULT 알림을 결제 리소스 연결로 저장한다")
    void publish_paid_savesNotificationWithAmount() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.PAID, AMOUNT);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.contains("80,000원 결제가 완료"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("REFUNDED: 금액을 담은 환불 완료 알림을 PAYMENT_RESULT로 저장한다(알림 유형을 늘리지 않는다, #37)")
    void publish_refunded_savesNotificationWithAmount() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.REFUNDED, AMOUNT);

        // NotificationType은 늘리지 않고 PAYMENT_RESULT를 재사용한다 — 보호자는 resourceType=PAYMENT와
        // 내용으로 어떤 결제의 환불인지 알 수 있다. 환불 사유는 병원 내부 감사값이라 알림에 담지 않는다.
        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.contains("80,000원이 전액 환불"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("OFFLINE_REQUIRED: 금액과 함께 현장 수납 안내 알림을 저장한다")
    void publish_offlineRequired_savesNotificationWithAmount() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.OFFLINE_REQUIRED, AMOUNT);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.contains("80,000원 자동 결제에 실패"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("OFFLINE_PAID: 금액과 함께 현장 수납 완료 알림을 저장한다")
    void publish_offlinePaid_savesNotificationWithAmount() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.OFFLINE_PAID, AMOUNT);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.contains("80,000원 결제가 완료"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("PENDING: 일시적 미확정이므로 결과 알림을 저장하지 않는다(무음 유지)")
    void publish_pending_doesNotSave() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.PENDING, AMOUNT);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("결제 확인 중 안내: PAYMENT_PENDING 유형으로 금액을 담아 멱등 저장(createIfAbsent)한다")
    void publishPendingNotice_savesPaymentPendingViaCreateIfAbsent() {
        publisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, AMOUNT);

        // 멱등은 createIfAbsent(존재 조회 후 저장)에 위임한다 — create가 아니라 createIfAbsent를 써야
        // 정산 여러 사이클과 전체 삭제 뒤에도 결제당 1회만 남는다(실제 저장·삭제 회귀는 통합 테스트에서 검증).
        verify(notificationService).createIfAbsent(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_PENDING),
                ArgumentMatchers.contains("80,000원 결제를 확인 중"),
                eq(NotificationResourceType.PAYMENT),
                eq(PAYMENT_ID)
        );
    }

    @Test
    @DisplayName("paymentId가 null이어도 결과 저장은 진행되고 resource_id만 null로 남는다")
    void publish_nullPaymentId_stillSaves() {
        publisher.publishChargeResult(GUARDIAN_ID, RESERVATION_ID, null, PaymentStatus.PAID, AMOUNT);

        verify(notificationService).create(
                eq(GUARDIAN_ID),
                eq(NotificationType.PAYMENT_RESULT),
                ArgumentMatchers.anyString(),
                eq(NotificationResourceType.PAYMENT),
                ArgumentMatchers.isNull()
        );
    }
}
