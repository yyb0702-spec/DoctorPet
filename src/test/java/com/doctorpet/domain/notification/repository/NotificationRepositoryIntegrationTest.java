package com.doctorpet.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import javax.sql.DataSource;
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
 * Level 3 — notifications 테이블의 수신자 인식 조회·읽음여부 필터·페이징·recipient 인덱스를 실제 MySQL로 검증한다
 * (docs/testing/verification-guide.md). 이 슬라이스(@DataJpaTest)는 트랜잭션 롤백·회원(MEMBER) 회귀에 집중한다 —
 * MigrationRunner가 뜨지 않는 슬라이스라 회원 행(member_id 채움)만 다뤄 member_id NOT NULL 여부와 무관하게 성립한다.
 * 병원(HOSPITAL) 단위 공유·격리·백필은 Runner가 member_id를 nullable로 완화한 전체 컨텍스트 통합 테스트에서 검증한다
 * (NotificationRecipientIntegrationTest·NotificationRecipientMigrationIntegrationTest). createdAt 자동 기록을 위해
 * JpaAuditingConfig를 import한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class NotificationRepositoryIntegrationTest {

    private static final Long MEMBER_A = 1001L;
    private static final Long MEMBER_B = 1002L;
    private static final NotificationRecipientType MEMBER = NotificationRecipientType.MEMBER;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("수신자(recipient_type·recipient_id)와 연결정보·read_at·created_at이 매핑대로 저장·조회된다")
    void recipientAndResourceColumnsAndTimestamps_persist() {
        Notification saved = notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 55L));
        entityManager.clear();

        Notification reloaded = notificationRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getRecipientType()).isEqualTo(MEMBER);
        assertThat(reloaded.getRecipientId()).isEqualTo(MEMBER_A);
        // MEMBER 수신은 하위호환용 member_id도 함께 채운다.
        assertThat(reloaded.getMemberId()).isEqualTo(MEMBER_A);
        assertThat(reloaded.getType()).isEqualTo(NotificationType.PAYMENT_RESULT);
        assertThat(reloaded.getResourceType()).isEqualTo(NotificationResourceType.PAYMENT);
        assertThat(reloaded.getResourceId()).isEqualTo(55L);
        assertThat(reloaded.getReadAt()).isNull();
        assertThat(reloaded.isRead()).isFalse();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("읽음 처리 후 read_at이 영속되고, 다시 markRead해도 최초 시각이 유지된다(멱등)")
    void readAt_persistsAndIsIdempotent() {
        Notification saved = notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 55L));
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
    @DisplayName("읽음여부 필터: 전체/미읽음/읽음을 수신자별로 정확히 구분하고 타 수신자 알림은 섞이지 않는다")
    void readFilter_isScopedPerRecipient() {
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 2L));
        Notification readOne = notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 3L));
        readOne.markRead(LocalDateTime.now());
        notificationRepository.saveAndFlush(readOne);
        notificationRepository.saveAndFlush(memberNotification(MEMBER_B, 4L));
        entityManager.clear();

        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(notificationRepository.findByRecipientTypeAndRecipientId(MEMBER, MEMBER_A, pageable)
                .getTotalElements()).isEqualTo(3);
        assertThat(notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_A, pageable)
                .getTotalElements()).isEqualTo(2);
        assertThat(notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNotNull(MEMBER, MEMBER_A, pageable)
                .getTotalElements()).isEqualTo(1);
        assertThat(notificationRepository.findByRecipientTypeAndRecipientId(MEMBER, MEMBER_B, pageable)
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("미읽음 개수: 수신자별 read_at NULL 건수만 세고 읽은 것·타 수신자 알림은 제외한다")
    void countUnread_isScopedPerRecipientAndExcludesRead() {
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 2L));
        Notification readOne = notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 3L));
        readOne.markRead(LocalDateTime.now());
        notificationRepository.saveAndFlush(readOne);
        notificationRepository.saveAndFlush(memberNotification(MEMBER_B, 4L));
        entityManager.clear();

        assertThat(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_A))
                .isEqualTo(2);
        assertThat(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_B))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("모두 읽음: 인증 수신자의 미읽음만 전부 읽음 처리하고 타 수신자 알림은 불변, 두 번째 호출은 0건(멱등)")
    void markAllReadForRecipient_scopedAndIdempotent() {
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 2L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_B, 3L));
        entityManager.clear();

        LocalDateTime now = LocalDateTime.now();
        int updated = notificationRepository.markAllReadForRecipient(MEMBER, MEMBER_A, now);
        entityManager.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_A)).isZero();
        // 타 수신자(MEMBER_B) 미읽음은 그대로 남는다.
        assertThat(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_B))
                .isEqualTo(1);

        // 두 번째 호출은 갱신할 미읽음이 없어 0건(멱등).
        assertThat(notificationRepository.markAllReadForRecipient(MEMBER, MEMBER_A, now.plusHours(1))).isZero();
    }

    @Test
    @DisplayName("전체 삭제: 인증 수신자 알림만 하드 삭제하고 타 수신자는 불변, 두 번째 호출은 0건(멱등)")
    void deleteAllForRecipient_scopedAndIdempotent() {
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 2L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_B, 3L));
        entityManager.clear();

        int deleted = notificationRepository.deleteAllForRecipient(MEMBER, MEMBER_A);
        entityManager.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_A)).isZero();
        // 타 수신자(MEMBER_B) 알림은 그대로 남는다.
        assertThat(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(MEMBER, MEMBER_B))
                .isEqualTo(1);

        // 두 번째 호출은 삭제할 게 없어 0건(멱등).
        assertThat(notificationRepository.deleteAllForRecipient(MEMBER, MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("페이징: size보다 많은 알림이 있으면 페이지 메타(totalElements·totalPages)가 올바르다")
    void paging_returnsCorrectMeta() {
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 1L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 2L));
        notificationRepository.saveAndFlush(memberNotification(MEMBER_A, 3L));
        entityManager.clear();

        PageRequest pageable = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> firstPage =
                notificationRepository.findByRecipientTypeAndRecipientId(MEMBER, MEMBER_A, pageable);

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();
    }

    /*
      PR #134 리뷰 지적을 이어받아, 인덱스를 추가만 하고 실제 생성·적용을 검증하지 않는 공백을 막는다. 수신자 모델 확장으로
      조회 축이 (recipient_type, recipient_id, read_at)로 바뀌었으므로 새 인덱스 idx_notifications_recipient_read가
      실제로 만들어지고 미읽음 조회 실행계획의 후보 인덱스에 포함되는지 information_schema·EXPLAIN으로 확인한다. 실행계획은
      possible_keys(적용 가능한 후보)로만 검증한다 — 최종 선택(key)은 공유 MySQL의 통계·데이터 분포에 따라 바뀔 수 있어
      단정하면 테스트가 데이터 상태에 취약해진다. "이 인덱스가 이 조건에 적용 가능한가"라는 스키마 계약만 고정한다.
     */
    @Test
    @DisplayName("idx_notifications_recipient_read 인덱스가 실제로 생성되고, 미읽음 조회 실행계획의 후보 인덱스에 포함된다")
    void recipientReadIndex_existsAndIsUsableByUnreadQuery() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement indexStmt = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 'notifications' "
                            + "AND index_name = 'idx_notifications_recipient_read'")) {
                try (ResultSet rs = indexStmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isGreaterThan(0);
                }
            }

            try (PreparedStatement explainStmt = connection.prepareStatement(
                    "EXPLAIN SELECT * FROM notifications "
                            + "WHERE recipient_type = ? AND recipient_id = ? AND read_at IS NULL")) {
                explainStmt.setString(1, MEMBER.name());
                explainStmt.setLong(2, MEMBER_A);
                try (ResultSet rs = explainStmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("possible_keys")).contains("idx_notifications_recipient_read");
                }
            }
        }
    }

    private Notification memberNotification(Long memberId, Long resourceId) {
        return Notification.create(
                MEMBER,
                memberId,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                resourceId
        );
    }
}
