package com.doctorpet.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.push.NotificationPusher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Level 3 — 저장 커밋 이후에만 실시간 전송이 실행되는 불변식을 실제 Spring 트랜잭션으로 검증한다
 * (PR #106 리뷰 P2). {@link NotificationServiceTest}는 Mock {@code ApplicationEventPublisher}로
 * 이벤트 발행 호출만 확인하므로, {@code @TransactionalEventListener(AFTER_COMMIT)}가 빠지거나
 * 다른 phase로 바뀌어도 그 테스트는 통과한다 — 실제 커밋·롤백 경계는 이 클래스가 검증한다.
 *
 * <p>테스트 메서드 자체를 {@code @Transactional}로 두면 JUnit이 끝에서 항상 롤백해 AFTER_COMMIT
 * 리스너가 영원히 실행되지 않으므로, {@link TransactionTemplate}으로 {@link NotificationService#create}를
 * 별도의 실제로 커밋/롤백되는 트랜잭션 안에서 호출한다. push는 {@link NotificationPusher}의 유일한
 * 빈(SseNotificationPusher)을 스파이로 감싸 실제 리스너 배선을 그대로 태운다.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class NotificationCommitPushIntegrationTest {

    private static final Long MEMBER_ID = 970001L;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private NotificationPusher notificationPusher;

    private TransactionTemplate transactionTemplate;
    private final List<Long> notificationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : notificationIds) {
            jdbcTemplate.update("delete from notifications where id = ?", id);
        }
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    @Test
    @DisplayName("저장 트랜잭션이 커밋되면 그 이후에 push가 정확히 1회 호출된다")
    void commit_triggersPushAfterward() {
        Long savedId = transactionTemplate().execute(status -> createAndTrack().getId());

        // execute()가 반환한 시점엔 커밋이 이미 끝났고, AFTER_COMMIT 리스너는 커밋 안에서 동기 실행된다.
        verify(notificationPusher, times(1)).push(eq(MEMBER_ID), any(NotificationResponse.class));
        assertThat(rowExists(savedId)).isTrue();
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 push가 호출되지 않고 저장도 남지 않는다")
    void rollback_neverTriggersPush() {
        Long attemptedId = transactionTemplate().execute(status -> {
            Long id = createAndTrack().getId();
            status.setRollbackOnly();
            return id;
        });

        verify(notificationPusher, never()).push(eq(MEMBER_ID), any(NotificationResponse.class));
        assertThat(rowExists(attemptedId)).isFalse();
    }

    @Test
    @DisplayName("push가 실패해도 이미 커밋된 저장 결과에는 영향을 주지 않는다")
    void pushFailure_doesNotAffectSavedNotification() {
        doThrow(new IllegalStateException("전송 채널 장애 시뮬레이션"))
                .when(notificationPusher)
                .push(eq(MEMBER_ID), any(NotificationResponse.class));

        Long savedId = transactionTemplate().execute(status -> createAndTrack().getId());

        // push 예외가 NotificationPushListener 안에서 삼켜지므로 호출자에게 전파되지 않고,
        // 저장은 이미 커밋을 마쳐 push 결과와 무관하게 남아 있어야 한다.
        verify(notificationPusher, times(1)).push(eq(MEMBER_ID), any(NotificationResponse.class));
        assertThat(rowExists(savedId)).isTrue();
    }

    private Notification createAndTrack() {
        Notification saved = notificationService.create(
                MEMBER_ID,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                1L
        );
        notificationIds.add(saved.getId());
        return saved;
    }

    private boolean rowExists(Long id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where id = ?", Long.class, id);
        return count != null && count > 0;
    }
}
