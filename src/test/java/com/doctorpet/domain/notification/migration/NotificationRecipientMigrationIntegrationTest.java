package com.doctorpet.domain.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 실제 MySQL에서 기존 notifications 행의 recipient 백필과 DDL 적용(recipient NOT NULL·member_id nullable·
 * 인덱스 교체)·다중 기동을 검증한다(고도화 3.10). 자동 실행을 끄고, @BeforeEach에서 recipient 컬럼을 nullable로 되돌리고
 * 인덱스를 legacy(member 기준)로 되돌려 "마이그레이션 이전" 상태를 만든 뒤 Runner를 직접 호출한다. @AfterEach에서 다시
 * 마이그레이션 완료 상태로 복원해 공유 DB를 다른 테스트가 기대하는 형태로 되돌린다(ReservationApprovalDeadline과 동일 관례).
 * member_id를 nullable로 완화하는 것은 데이터와 무관하게 항상 안전하므로 legacy 시뮬레이션에서 member_id는 건드리지 않는다.
 */
@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class NotificationRecipientMigrationIntegrationTest {

    private static final String CREATED_INDEX = "idx_notifications_recipient_created";
    private static final String READ_INDEX = "idx_notifications_recipient_read";
    private static final String LEGACY_CREATED_INDEX = "idx_notifications_member_created";
    private static final String LEGACY_READ_INDEX = "idx_notifications_member_read";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long legacyNotificationId;

    @BeforeEach
    void prepareLegacySchema() {
        deleteMarker();
        // recipient 컬럼을 nullable로 되돌려 recipient가 비어 있는 legacy 행을 넣을 수 있게 한다.
        jdbcTemplate.execute("alter table notifications modify column recipient_type varchar(20) null");
        jdbcTemplate.execute("alter table notifications modify column recipient_id bigint null");
        dropIndexIfExists(CREATED_INDEX);
        dropIndexIfExists(READ_INDEX);
        createIndexIfMissing(LEGACY_CREATED_INDEX, "member_id, created_at");
        createIndexIfMissing(LEGACY_READ_INDEX, "member_id, read_at");
    }

    @AfterEach
    void restoreMigratedSchema() {
        if (legacyNotificationId != null) {
            jdbcTemplate.update("delete from notifications where id = ?", legacyNotificationId);
        }
        // 남은 legacy 행이 있으면 백필한 뒤 마이그레이션 완료 스키마로 되돌린다.
        jdbcTemplate.update("""
                update notifications
                   set recipient_type = 'MEMBER',
                       recipient_id = member_id
                 where (recipient_type is null or recipient_id is null)
                   and member_id is not null
                """);
        jdbcTemplate.execute("alter table notifications modify column recipient_type varchar(20) not null");
        jdbcTemplate.execute("alter table notifications modify column recipient_id bigint not null");
        jdbcTemplate.execute("alter table notifications modify column member_id bigint null");
        createIndexIfMissing(CREATED_INDEX, "recipient_type, recipient_id, created_at");
        createIndexIfMissing(READ_INDEX, "recipient_type, recipient_id, read_at");
        dropIndexIfExists(LEGACY_CREATED_INDEX);
        dropIndexIfExists(LEGACY_READ_INDEX);
        deleteMarker();
    }

    @Test
    @DisplayName("기존 행을 (MEMBER, member_id)로 백필한 뒤 recipient NOT NULL·member_id nullable·recipient 인덱스를 적용한다")
    void legacyRows_areBackfilledBeforeConstraints() {
        long memberId = insertLegacyMemberNotification();

        new NotificationRecipientMigrationRunner(jdbcTemplate).run(null);

        assertThat(recipientType(legacyNotificationId)).isEqualTo("MEMBER");
        assertThat(recipientId(legacyNotificationId)).isEqualTo(memberId);
        assertThat(columnNullable("recipient_type")).isFalse();
        assertThat(columnNullable("recipient_id")).isFalse();
        assertThat(columnNullable("member_id")).isTrue();
        assertThat(indexExists(CREATED_INDEX)).isTrue();
        assertThat(indexExists(READ_INDEX)).isTrue();
        assertThat(indexExists(LEGACY_CREATED_INDEX)).isFalse();
        assertThat(indexExists(LEGACY_READ_INDEX)).isFalse();
        assertThat(markerExists()).isTrue();
    }

    @Test
    @DisplayName("마커가 있는데 목표 스키마(recipient NOT NULL)가 아니면 부팅 오류로 드러낸다")
    void appliedMarkerWithoutTargetSchema_failsFast() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, NotificationRecipientMigrationRunner.MIGRATION_KEY);

        assertThatThrownBy(() -> new NotificationRecipientMigrationRunner(jdbcTemplate).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT NULL 제약이 없습니다");
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 최초 기동해도 DB 잠금으로 한 번만 적용한다")
    void concurrentRunners_areSerializedByDatabaseLock() throws InterruptedException {
        long memberId = insertLegacyMemberNotification();
        NotificationRecipientMigrationRunner runner = new NotificationRecipientMigrationRunner(jdbcTemplate);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    runner.run(null);
                } catch (Throwable throwable) {
                    errors.add(throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(columnNullable("recipient_type")).isFalse();
        assertThat(columnNullable("recipient_id")).isFalse();
        assertThat(columnNullable("member_id")).isTrue();
        assertThat(indexExists(CREATED_INDEX)).isTrue();
        assertThat(indexExists(READ_INDEX)).isTrue();
        assertThat(markerExists()).isTrue();
        assertThat(recipientType(legacyNotificationId)).isEqualTo("MEMBER");
        assertThat(recipientId(legacyNotificationId)).isEqualTo(memberId);
    }

    // recipient가 비어 있는 legacy 회원 알림을 넣는다(member_id만 채움). 유일 member_id로 다른 테스트 데이터와 격리한다.
    private long insertLegacyMemberNotification() {
        long memberId = Math.abs(System.nanoTime());
        jdbcTemplate.update("""
                insert into notifications
                       (member_id, type, content, resource_type, resource_id, created_at, updated_at)
                values (?, 'PAYMENT_RESULT', '진료비 결제가 완료되었습니다.', 'PAYMENT', 55, now(6), now(6))
                """, memberId);
        legacyNotificationId = jdbcTemplate.queryForObject(
                "select id from notifications where member_id = ? order by id desc limit 1",
                Long.class, memberId);
        return memberId;
    }

    private String recipientType(Long id) {
        return jdbcTemplate.queryForObject(
                "select recipient_type from notifications where id = ?", String.class, id);
    }

    private Long recipientId(Long id) {
        return jdbcTemplate.queryForObject(
                "select recipient_id from notifications where id = ?", Long.class, id);
    }

    private boolean columnNullable(String columnName) {
        String nullable = jdbcTemplate.queryForObject("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = ?
                """, String.class, columnName);
        return "YES".equalsIgnoreCase(nullable);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'notifications'
                   and index_name = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }

    private void createIndexIfMissing(String indexName, String columns) {
        if (!indexExists(indexName)) {
            jdbcTemplate.execute("create index " + indexName + " on notifications (" + columns + ")");
        }
    }

    private void dropIndexIfExists(String indexName) {
        if (indexExists(indexName)) {
            jdbcTemplate.execute("drop index " + indexName + " on notifications");
        }
    }

    private boolean markerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class, NotificationRecipientMigrationRunner.MIGRATION_KEY);
        return count != null && count > 0;
    }

    private void deleteMarker() {
        jdbcTemplate.update("delete from schema_migrations where migration_key = ?",
                NotificationRecipientMigrationRunner.MIGRATION_KEY);
    }
}
