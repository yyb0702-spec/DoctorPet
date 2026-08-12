package com.doctorpet.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.adapter.StoringPaymentNotificationPublisher;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
                .findByRecipientTypeAndRecipientId(
                        NotificationRecipientType.MEMBER, GUARDIAN_ID, PageRequest.of(0, 100))
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
    @DisplayName("결제 결과 발행 시 보호자에게 금액을 담은 PAYMENT_RESULT 알림이 결제 리소스 연결로 저장된다")
    void publishChargeResult_persistsNotification() {
        paymentNotificationPublisher.publishChargeResult(
                GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.PAID, 80_000);

        List<Notification> notifications = notificationRepository
                .findByRecipientTypeAndRecipientId(
                        NotificationRecipientType.MEMBER, GUARDIAN_ID,
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();

        assertThat(notifications).hasSize(1);
        Notification saved = notifications.get(0);
        assertThat(saved.getType()).isEqualTo(NotificationType.PAYMENT_RESULT);
        assertThat(saved.getResourceType()).isEqualTo(NotificationResourceType.PAYMENT);
        assertThat(saved.getResourceId()).isEqualTo(PAYMENT_ID);
        assertThat(saved.getContent()).contains("80,000원");
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    @DisplayName("결제 확인 중 안내를 여러 번 발행해도 같은 결제엔 PAYMENT_PENDING 알림이 1건만 저장된다(멱등)")
    void publishPendingNotice_isIdempotentAcrossCycles() {
        // 정산이 여러 사이클 돌아 같은 결제에 반복 호출되는 상황을 재현한다.
        paymentNotificationPublisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, 80_000);
        paymentNotificationPublisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, 80_000);
        paymentNotificationPublisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, 80_000);

        List<Notification> notifications = notificationRepository
                .findByRecipientTypeAndRecipientId(
                        NotificationRecipientType.MEMBER, GUARDIAN_ID,
                        PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();

        assertThat(notifications).hasSize(1);
        Notification saved = notifications.get(0);
        assertThat(saved.getType()).isEqualTo(NotificationType.PAYMENT_PENDING);
        assertThat(saved.getResourceType()).isEqualTo(NotificationResourceType.PAYMENT);
        assertThat(saved.getResourceId()).isEqualTo(PAYMENT_ID);
        assertThat(saved.getContent()).contains("80,000원").contains("확인 중");
    }

    @Test
    @DisplayName("결제 확인 중 안내를 동시에 여러 스레드가 발행해도 dedup_key UNIQUE로 1건만 저장된다(동시 호출 멱등)")
    void publishPendingNotice_isIdempotentUnderConcurrency() throws Exception {
        // 락 밖 경로(웹훅 단건 트리거)가 배치와 겹쳐 같은 결제를 동시에 처리하는 경합을 재현한다. 존재조회→저장이
        // 원자적이지 않아 여러 스레드가 모두 "없음"을 읽어도, dedup_key UNIQUE가 실제 저장을 1건으로 제한해야 한다.
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    paymentNotificationPublisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, 80_000);
                    return null;
                }));
            }
            ready.await();
            start.countDown(); // 모든 스레드를 동시에 출발시켜 경합을 최대화한다.
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<Notification> pendings = notificationRepository
                .findByRecipientTypeAndRecipientId(
                        NotificationRecipientType.MEMBER, GUARDIAN_ID,
                        PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent()
                .stream()
                .filter(n -> n.getType() == NotificationType.PAYMENT_PENDING)
                .toList();

        assertThat(pendings).hasSize(1);
        assertThat(pendings.get(0).getResourceId()).isEqualTo(PAYMENT_ID);
    }
}
