package com.doctorpet.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.adapter.StoringPaymentNotificationPublisher;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Level 3 — 결제 알림 발행 배선 검증. #39의 저장형 구현이 로그 스텁(LoggingPaymentNotificationPublisher)을
 * @ConditionalOnMissingBean으로 대체해 PaymentNotificationPublisher 타입으로 주입되는지, 그리고 발행 시
 * notifications 테이블에 실제로 저장되는지(이벤트→알림 생성) 실제 MySQL로 확인한다. 전체 컨텍스트가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class PaymentNotificationWiringIntegrationTest {

    private static final Long GUARDIAN_ID = 987_654_321L;
    private static final Long RESERVATION_ID = 424_242L;
    private static final Long PAYMENT_ID = 555_555L;

    @Autowired
    private PaymentNotificationPublisher paymentNotificationPublisher;

    @Autowired
    private NotificationRepository notificationRepository;

    @AfterEach
    void cleanUp() {
        List<Notification> created = notificationRepository
                .findByMemberId(GUARDIAN_ID, PageRequest.of(0, 100))
                .getContent();
        notificationRepository.deleteAll(created);
    }

    @Test
    @DisplayName("주입된 PaymentNotificationPublisher 빈은 저장형 구현(StoringPaymentNotificationPublisher)이다")
    void publisherBean_isStoringImplementation() {
        assertThat(paymentNotificationPublisher)
                .isInstanceOf(StoringPaymentNotificationPublisher.class);
    }

    @Test
    @DisplayName("결제 결과 발행 시 보호자에게 PAYMENT_RESULT 알림이 결제 리소스 연결로 저장된다")
    void publishChargeResult_persistsNotification() {
        paymentNotificationPublisher.publishChargeResult(
                GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.PAID);

        List<Notification> notifications = notificationRepository
                .findByMemberId(GUARDIAN_ID, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();

        assertThat(notifications).hasSize(1);
        Notification saved = notifications.get(0);
        assertThat(saved.getType()).isEqualTo(NotificationType.PAYMENT_RESULT);
        assertThat(saved.getResourceType()).isEqualTo(NotificationResourceType.PAYMENT);
        assertThat(saved.getResourceId()).isEqualTo(PAYMENT_ID);
        assertThat(saved.isRead()).isFalse();
    }
}
