package com.doctorpet.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Level 3 — notifications 테이블 DDL·연결정보 컬럼·읽음여부 필터·페이징·read_at 영속을 실제 MySQL로 검증한다
 * (docs/testing/verification-guide.md). H2 등 임베디드 DB 의존성이 없어 실제 DataSource(docker MySQL 3307)를
 * 그대로 쓰며, 의도를 분명히 하기 위해 replace=NONE을 명시한다. createdAt 자동 기록을 위해 JpaAuditingConfig를 import한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class NotificationRepositoryIntegrationTest {

    private static final Long MEMBER_A = 1001L;
    private static final Long MEMBER_B = 1002L;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("연결정보(resource_type·resource_id)와 read_at·created_at이 매핑대로 저장·조회된다")
    void resourceColumnsAndTimestamps_persist() {
        Notification saved = notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 55L));
        entityManager.clear();

        Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getType()).isEqualTo(NotificationType.PAYMENT_RESULT);
        assertThat(reloaded.getContent()).isEqualTo("진료비 결제가 완료되었습니다.");
        assertThat(reloaded.getResourceType()).isEqualTo(NotificationResourceType.PAYMENT);
        assertThat(reloaded.getResourceId()).isEqualTo(55L);
        assertThat(reloaded.getReadAt()).isNull();
        assertThat(reloaded.isRead()).isFalse();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("읽음 처리 후 read_at이 영속되고, 다시 markRead해도 최초 시각이 유지된다(멱등)")
    void readAt_persistsAndIsIdempotent() {
        Notification saved = notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 55L));
        Long id = saved.getId();

        Notification first = notificationRepository.findById(id).orElseThrow();
        first.markRead(LocalDateTime.now());
        notificationRepository.saveAndFlush(first);
        entityManager.clear();
        LocalDateTime firstReadAt = notificationRepository.findById(id).orElseThrow().getReadAt();
        assertThat(firstReadAt).isNotNull();

        Notification again = notificationRepository.findById(id).orElseThrow();
        again.markRead(LocalDateTime.now().plusHours(1));
        notificationRepository.saveAndFlush(again);
        entityManager.clear();

        assertThat(notificationRepository.findById(id).orElseThrow().getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("읽음여부 필터: 전체/미읽음/읽음을 수신자별로 정확히 구분하고 타 사용자 알림은 섞이지 않는다")
    void readFilter_isScopedPerMember() {
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 2L));
        Notification readOne = notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 3L));
        readOne.markRead(LocalDateTime.now());
        notificationRepository.saveAndFlush(readOne);
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_B, 4L));
        entityManager.clear();

        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(notificationRepository.findByMemberId(MEMBER_A, pageable).getTotalElements()).isEqualTo(3);
        assertThat(notificationRepository.findByMemberIdAndReadAtIsNull(MEMBER_A, pageable).getTotalElements())
                .isEqualTo(2);
        assertThat(notificationRepository.findByMemberIdAndReadAtIsNotNull(MEMBER_A, pageable).getTotalElements())
                .isEqualTo(1);
        assertThat(notificationRepository.findByMemberId(MEMBER_B, pageable).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("미읽음 개수: 수신자별 read_at NULL 건수만 세고 읽은 것·타 사용자 알림은 제외한다")
    void countUnread_isScopedPerMemberAndExcludesRead() {
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 2L));
        Notification readOne = notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 3L));
        readOne.markRead(LocalDateTime.now());
        notificationRepository.saveAndFlush(readOne);
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_B, 4L));
        entityManager.clear();

        assertThat(notificationRepository.countByMemberIdAndReadAtIsNull(MEMBER_A)).isEqualTo(2);
        assertThat(notificationRepository.countByMemberIdAndReadAtIsNull(MEMBER_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("모두 읽음: 인증 회원의 미읽음만 전부 읽음 처리하고 타 사용자 알림은 불변, 두 번째 호출은 0건(멱등)")
    void markAllReadForMember_scopedAndIdempotent() {
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 2L));
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_B, 3L));
        entityManager.clear();

        LocalDateTime now = LocalDateTime.now();
        int updated = notificationRepository.markAllReadForMember(MEMBER_A, now);
        entityManager.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.countByMemberIdAndReadAtIsNull(MEMBER_A)).isZero();
        // 타 사용자(MEMBER_B) 미읽음은 그대로 남는다.
        assertThat(notificationRepository.countByMemberIdAndReadAtIsNull(MEMBER_B)).isEqualTo(1);

        // 두 번째 호출은 갱신할 미읽음이 없어 0건(멱등).
        assertThat(notificationRepository.markAllReadForMember(MEMBER_A, now.plusHours(1))).isZero();
    }

    @Test
    @DisplayName("페이징: size보다 많은 알림이 있으면 페이지 메타(totalElements·totalPages)가 올바르다")
    void paging_returnsCorrectMeta() {
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 2L));
        notificationRepository.saveAndFlush(paymentNotification(MEMBER_A, 3L));
        entityManager.clear();

        PageRequest pageable = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> firstPage = notificationRepository.findByMemberId(MEMBER_A, pageable);

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();
    }

    private Notification paymentNotification(Long memberId, Long resourceId) {
        return Notification.create(
                memberId,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                resourceId
        );
    }
}
