package com.doctorpet.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.global.time.TimePolicy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Level 3 — 알림 읽음 처리 동시성 통합 검증(PR #87 P2). 같은 미읽음 알림에 서로 다른 시각을 기록하려는 두 요청이
 * 동시에 들어와도 WHERE read_at IS NULL 조건부 UPDATE가 최초 1회만 성립시켜(정확히 1건), 최초로 확정된 read_at이
 * 뒤늦은 요청에 덮어써지지 않음을 실제 MySQL로 검증한다. 병원 단위 공유(고도화 3.10)에서는 서로 다른 스태프가 같은
 * 알림을 동시에 읽는 것이 정상 경로라 이 멱등이 회원 케이스보다 더 중요하다 — 회원·병원 두 수신자 모두 검증한다.
 * 각 스레드를 TransactionTemplate으로 감싸 "요청당 1 트랜잭션"(서비스 @Transactional)을 그대로 모사한다.
 * 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class NotificationMarkReadConcurrencyIntegrationTest {

    private static final Long MEMBER_ID = 9001L;
    private static final Long HOSPITAL_ID = 9002L;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long notificationId;

    @AfterEach
    void tearDown() {
        if (notificationId != null) {
            notificationRepository.deleteById(notificationId);
        }
    }

    @Test
    @DisplayName("회원 수신: 같은 미읽음 알림에 서로 다른 시각의 읽음 처리가 동시에 들어와도 read_at은 정확히 1건만 확정된다")
    void concurrentMarkRead_member_appliesExactlyOnce() throws InterruptedException {
        Notification saved = notificationRepository.saveAndFlush(
                Notification.create(
                        NotificationRecipientType.MEMBER,
                        MEMBER_ID,
                        NotificationType.PAYMENT_RESULT,
                        "진료비 결제가 완료되었습니다.",
                        NotificationResourceType.PAYMENT,
                        55L));
        notificationId = saved.getId();

        assertExactlyOnceUnderConcurrency(NotificationRecipientType.MEMBER, MEMBER_ID);
    }

    @Test
    @DisplayName("병원 단위 공유: 여러 스태프가 같은 병원 알림을 동시에 읽어도 read_at은 정확히 1건만 확정된다(개인 읽음 복제 없음)")
    void concurrentMarkRead_hospital_appliesExactlyOnce() throws InterruptedException {
        Notification saved = notificationRepository.saveAndFlush(
                Notification.create(
                        NotificationRecipientType.HOSPITAL,
                        HOSPITAL_ID,
                        NotificationType.RESERVATION_CONFIRMED,
                        "새 예약 요청이 접수되었습니다.",
                        NotificationResourceType.RESERVATION,
                        77L));
        notificationId = saved.getId();

        assertExactlyOnceUnderConcurrency(NotificationRecipientType.HOSPITAL, HOSPITAL_ID);
    }

    private void assertExactlyOnceUnderConcurrency(
            NotificationRecipientType recipientType, Long recipientId) throws InterruptedException {
        // 두 요청이 서로 다른 시각을 쓰도록 1시간 차이를 둔다 — 승자의 시각으로 확정됐는지(덮어쓰기 없음)를 단언하기 위함.
        LocalDateTime early = LocalDateTime.now(TimePolicy.SEOUL_ZONE_ID).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime late = early.plusHours(1);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Integer> earlyUpdated = new AtomicReference<>();
        AtomicReference<Integer> lateUpdated = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> runMarkRead(tx, start, done, failure, earlyUpdated, recipientType, recipientId, early));
        pool.submit(() -> runMarkRead(tx, start, done, failure, lateUpdated, recipientType, recipientId, late));

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 어떤 읽음 처리도 예외(500)로 새지 않는다.
        assertThat(failure.get()).isNull();

        // 상태 전이는 정확히 1건 — 한 요청만 갱신 1건, 다른 요청은 이미 읽음이라 갱신 0건.
        int appliedCount = earlyUpdated.get() + lateUpdated.get();
        assertThat(appliedCount).isEqualTo(1);

        // 최종 read_at은 갱신 1건을 반환한 승자의 시각이고, 늦은 요청이 이를 덮어쓰지 않았다.
        LocalDateTime expected = earlyUpdated.get() == 1 ? early : late;
        LocalDateTime persisted = notificationRepository.findById(notificationId).orElseThrow().getReadAt();
        assertThat(persisted).isEqualTo(expected);
    }

    private void runMarkRead(
            TransactionTemplate tx,
            CountDownLatch start,
            CountDownLatch done,
            AtomicReference<Throwable> failure,
            AtomicReference<Integer> sink,
            NotificationRecipientType recipientType,
            Long recipientId,
            LocalDateTime now
    ) {
        try {
            start.await();
            sink.set(tx.execute(status ->
                    notificationRepository.markReadIfUnread(notificationId, recipientType, recipientId, now)));
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            done.countDown();
        }
    }
}
