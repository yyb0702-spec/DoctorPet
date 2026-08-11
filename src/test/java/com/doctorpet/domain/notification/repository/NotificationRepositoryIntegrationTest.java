package com.doctorpet.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.entity.Notification;
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

    @Autowired
    private DataSource dataSource;

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

    /*
      PR #134 리뷰 지적 — 인덱스를 추가해 놓고 실제 생성·사용 여부를 검증하는 테스트가 없어
      인덱스가 만들어지지 않아도 다른 테스트는 그대로 통과했다. information_schema로 실제
      생성을 확인한다. 실행계획은 possible_keys(이 조건에 실제로 쓸 수 있는 인덱스 후보)로
      검증한다 — 최종 선택(key)은 이 로컬 MySQL이 여러 테스트·수동 실행이 공유하는 인스턴스라
      테이블 통계·기존 데이터 분포에 따라 바뀔 수 있어 단정하면 이 테스트 자체가 데이터
      상태에 취약해진다. "이 인덱스가 이 조건에 적용 가능한가"라는 스키마 계약만 고정한다.
     */
    @Test
    @DisplayName("idx_notifications_member_read 인덱스가 실제로 생성되고, 미읽음 조회 실행계획의 후보 인덱스에 포함된다")
    void memberReadIndex_existsAndIsUsableByUnreadQuery() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement indexStmt = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 'notifications' "
                            + "AND index_name = 'idx_notifications_member_read'")) {
                try (ResultSet rs = indexStmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isGreaterThan(0);
                }
            }

            try (PreparedStatement explainStmt = connection.prepareStatement(
                    "EXPLAIN SELECT * FROM notifications WHERE member_id = ? AND read_at IS NULL")) {
                explainStmt.setLong(1, MEMBER_A);
                try (ResultSet rs = explainStmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("possible_keys")).contains("idx_notifications_member_read");
                }
            }
        }
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
