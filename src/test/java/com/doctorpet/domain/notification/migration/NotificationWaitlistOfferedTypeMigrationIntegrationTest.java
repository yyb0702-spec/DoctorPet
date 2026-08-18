package com.doctorpet.domain.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL에서 기존 알림 enum 값 보존과 대기열 승급 알림 유형 추가를 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3307/doctorpet?serverTimezone=Asia/Seoul&characterEncoding=UTF-8}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:root}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:root}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=localhost",
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "jwt.secret=doctorpet-waitlist-notification-migration-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class NotificationWaitlistOfferedTypeMigrationIntegrationTest {

    private static final String BEFORE_WAITLIST_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이 테스트는 type 컬럼을 대기열 유형 이전 ENUM으로 되돌리므로, 복원도 현재 목표 스키마 전체로 해야 한다 —
    // 대기열 러너만 돌리면 RESERVATION_REQUESTED(#166)가 빠진 채 남아 이후 테스트의 병원 알림 저장이 실패한다.
    @AfterEach
    void restoreMigrationState() {
        new NotificationWaitlistOfferedTypeMigrationRunner(jdbcTemplate).migrateBeforeJpa();
        new NotificationReservationRequestedTypeMigrationRunner(jdbcTemplate).migrateBeforeJpa();
    }

    @Test
    @DisplayName("기존 PAYMENT_PENDING을 보존하면서 RESERVATION_WAITLIST_OFFERED enum을 추가하고 재실행해도 안전하다")
    void migration_preservesExistingValuesAndIsIdempotent() {
        jdbcTemplate.update("delete from notifications where type = 'RESERVATION_WAITLIST_OFFERED'");
        jdbcTemplate.execute("alter table notifications modify column type " + BEFORE_WAITLIST_ENUM + " not null");
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                NotificationWaitlistOfferedTypeMigrationRunner.MIGRATION_KEY
        );
        NotificationWaitlistOfferedTypeMigrationRunner runner =
                new NotificationWaitlistOfferedTypeMigrationRunner(jdbcTemplate);

        runner.migrateBeforeJpa();
        runner.run(null);

        assertThat(notificationTypeColumn()).contains("PAYMENT_PENDING");
        assertThat(notificationTypeColumn()).contains("RESERVATION_WAITLIST_OFFERED");
        assertThat(migrationMarkerExists()).isTrue();
    }

    private String notificationTypeColumn() {
        return jdbcTemplate.queryForObject("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = 'type'
                """, String.class);
    }

    private boolean migrationMarkerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                NotificationWaitlistOfferedTypeMigrationRunner.MIGRATION_KEY
        );
        return count != null && count == 1;
    }
}
