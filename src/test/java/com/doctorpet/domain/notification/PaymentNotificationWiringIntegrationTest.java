package com.doctorpet.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.adapter.StoringPaymentNotificationPublisher;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.migration.NotificationDeliveryMarkMigrationRunner;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.domain.notification.repository.NotificationDeliveryMarkRepository;
import com.doctorpet.domain.notification.service.NotificationRecipient;
import com.doctorpet.domain.notification.service.NotificationService;
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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Autowired
    private NotificationDeliveryMarkRepository notificationDeliveryMarkRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationDeliveryMarkMigrationRunner notificationDeliveryMarkMigrationRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        List<Notification> created = notificationRepository
                .findByRecipientTypeAndRecipientId(
                        NotificationRecipientType.MEMBER, GUARDIAN_ID, PageRequest.of(0, 100))
                .getContent();
        notificationRepository.deleteAll(created);
        jdbcTemplate.update("delete from notification_delivery_marks where dedup_key = ?",
                Notification.dedupKey(GUARDIAN_ID, NotificationType.PAYMENT_PENDING,
                        NotificationResourceType.PAYMENT, PAYMENT_ID));
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
    @DisplayName("결제 확인 중 안내를 동시에 여러 스레드가 발행해도 delivery mark UNIQUE로 1건만 저장된다(동시 호출 멱등)")
    void publishPendingNotice_isIdempotentUnderConcurrency() throws Exception {
        // 락 밖 경로(웹훅 단건 트리거)가 배치와 겹쳐 같은 결제를 동시에 처리하는 경합을 재현한다. 존재조회→저장이
        // 원자적이지 않아 여러 스레드가 모두 "없음"을 읽어도, delivery mark UNIQUE가 실제 저장을 1건으로 제한해야 한다.
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

    @Test
    @DisplayName("전체 삭제 뒤에도 결제 확인 중 안내의 발행 이력은 남아 같은 결제에 재발행되지 않는다")
    void deleteAll_preservesPendingNoticeDeliveryMark() {
        paymentNotificationPublisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, 80_000);
        String dedupKey = Notification.dedupKey(
                GUARDIAN_ID, NotificationType.PAYMENT_PENDING, NotificationResourceType.PAYMENT, PAYMENT_ID);

        assertThat(notificationDeliveryMarkRepository.existsByDedupKey(dedupKey)).isTrue();
        assertThat(notificationService.deleteAll(NotificationRecipient.member(GUARDIAN_ID)).deletedCount()).isEqualTo(1);

        // 다음 정산 주기의 같은 발행 요청을 재현한다. 표시 행은 삭제됐지만 delivery mark가 남아 있어야 한다.
        paymentNotificationPublisher.publishPendingNotice(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, 80_000);

        assertThat(notificationRepository.findByRecipientTypeAndRecipientId(
                NotificationRecipientType.MEMBER, GUARDIAN_ID, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(notificationDeliveryMarkRepository.existsByDedupKey(dedupKey)).isTrue();
    }

    @Test
    @DisplayName("기존 dedup_key를 발행 마커로 백필하고 구버전 INSERT도 트리거로 동기화한다")
    void deliveryMarkMigration_backfillsLegacyRowsAndTracksLegacyInserts() throws Exception {
        long firstPaymentId = System.nanoTime();
        long secondPaymentId = firstPaymentId + 1;
        String firstDedupKey = Notification.dedupKey(
                GUARDIAN_ID, NotificationType.PAYMENT_PENDING, NotificationResourceType.PAYMENT, firstPaymentId);
        String secondDedupKey = Notification.dedupKey(
                GUARDIAN_ID, NotificationType.PAYMENT_PENDING, NotificationResourceType.PAYMENT, secondPaymentId);

        try {
            // 구버전 배포 상태를 재현한다. mark 테이블은 아직 비어 있고, notifications INSERT가 mark에 전파되는
            // 트리거·마이그레이션 기록도 없다. 첫 행은 트리거 설치 전이라 반드시 백필로만 복구돼야 한다.
            jdbcTemplate.execute("drop trigger if exists trg_notifications_delivery_mark");
            jdbcTemplate.update("delete from notification_delivery_marks where dedup_key in (?, ?)", firstDedupKey, secondDedupKey);
            jdbcTemplate.update("delete from schema_migrations where migration_key = 'notification_delivery_marks_v1'");
            notificationRepository.saveAndFlush(Notification.createIdempotent(
                    GUARDIAN_ID, NotificationType.PAYMENT_PENDING, "기존 결제 확인 중", NotificationResourceType.PAYMENT,
                    firstPaymentId));

            notificationDeliveryMarkMigrationRunner.run(new DefaultApplicationArguments(new String[0]));

            assertThat(notificationDeliveryMarkRepository.existsByDedupKey(firstDedupKey)).isTrue();
            Integer notNullColumns = jdbcTemplate.queryForObject("""
                    select count(*) from information_schema.columns
                     where table_schema = database()
                       and table_name = 'notification_delivery_marks'
                       and column_name in ('dedup_key', 'created_at')
                       and is_nullable = 'NO'
                    """, Integer.class);
            assertThat(notNullColumns).isEqualTo(2);
            // 설치된 AFTER INSERT 트리거는 아직 구버전이 쓴 새 notifications 행도 mark에 동기화한다.
            notificationRepository.saveAndFlush(Notification.createIdempotent(
                    GUARDIAN_ID, NotificationType.PAYMENT_PENDING, "새 결제 확인 중", NotificationResourceType.PAYMENT,
                    secondPaymentId));
            assertThat(notificationDeliveryMarkRepository.existsByDedupKey(secondDedupKey)).isTrue();
        } finally {
            // 이 테스트가 중간에 실패해도 다음 테스트가 trigger·마커 없이 실행되지 않게 배포 후의 정상 스키마를 복구한다.
            notificationDeliveryMarkMigrationRunner.run(new DefaultApplicationArguments(new String[0]));
            jdbcTemplate.update("delete from notifications where dedup_key in (?, ?)", firstDedupKey, secondDedupKey);
            jdbcTemplate.update("delete from notification_delivery_marks where dedup_key in (?, ?)", firstDedupKey, secondDedupKey);
        }
    }
}
