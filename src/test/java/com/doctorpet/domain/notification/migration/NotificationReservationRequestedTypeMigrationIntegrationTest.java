package com.doctorpet.domain.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 실제 MySQL에서 기존 알림 enum 값을 보존하면서 병원 수신 유형(RESERVATION_REQUESTED, #166)이
 * 추가되는지, 재실행해도 안전한지 검증한다.
 */
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
        "jwt.secret=doctorpet-reservation-requested-migration-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class NotificationReservationRequestedTypeMigrationIntegrationTest {

    // #166 이전 목표 ENUM(대기열 유형까지만 적용된 상태).
    private static final String BEFORE_REQUESTED_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED',"
                    + "'RESERVATION_WAITLIST_OFFERED')";
    // 오타 값이 아직 남아 있는(대기열 유형 마이그레이션 이전) 상태.
    private static final String LEGACY_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELLED','RESERVATION_HOSPITAL_CANCELED',"
                    + "'RESERVATION_REJECTED','RESERVATION_WAITLIST_OFFERED')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreMigrationState() {
        // 오타 값 정정을 포함한 현재 목표 스키마 전체로 되돌린다(러너 두 개의 합집합).
        new NotificationWaitlistOfferedTypeMigrationRunner(jdbcTemplate).migrateBeforeJpa();
        new NotificationReservationRequestedTypeMigrationRunner(jdbcTemplate).migrateBeforeJpa();
    }

    @Test
    @DisplayName("기존 enum 값을 보존하면서 RESERVATION_REQUESTED를 추가하고 재실행해도 안전하다")
    void migration_preservesExistingValuesAndIsIdempotent() {
        jdbcTemplate.update("delete from notifications where type = 'RESERVATION_REQUESTED'");
        jdbcTemplate.execute("alter table notifications modify column type " + BEFORE_REQUESTED_ENUM + " not null");
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                NotificationReservationRequestedTypeMigrationRunner.MIGRATION_KEY
        );
        NotificationReservationRequestedTypeMigrationRunner runner =
                new NotificationReservationRequestedTypeMigrationRunner(jdbcTemplate);

        runner.migrateBeforeJpa();
        // 두 번째 실행(부팅 후 ApplicationRunner 경로)이 이미 적용된 스키마를 깨지 않아야 한다.
        runner.run(null);

        String columnType = notificationTypeColumn();
        assertThat(columnType).contains("RESERVATION_REQUESTED");
        assertThat(columnType).contains("RESERVATION_WAITLIST_OFFERED");
        assertThat(columnType).contains("PAYMENT_PENDING");
        assertThat(columnType).contains("RESERVATION_HOSPITAL_CANCELED");
        assertThat(migrationMarkerExists()).isTrue();
    }

    @Test
    @DisplayName("오타 값 정정이 끝나지 않은 DB에서는 데이터를 건드리지 않고 멈춘다")
    void migration_beforeLegacyTypoFix_failsWithoutAlteringColumn() {
        // 대기열 유형 마이그레이션이 먼저 끝나야 한다 — 여기서 목표 ENUM을 적용하면 오타 값을 쓰는 기존 행이
        // 잘려 나간다. 순서가 뒤집힌 상황을 조용히 통과시키지 않는 것이 이 테스트의 계약이다.
        jdbcTemplate.execute("alter table notifications modify column type " + LEGACY_ENUM + " not null");
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                NotificationReservationRequestedTypeMigrationRunner.MIGRATION_KEY
        );

        assertThatThrownBy(() -> new NotificationReservationRequestedTypeMigrationRunner(jdbcTemplate)
                .migrateBeforeJpa())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESERVATION_HOSPITAL_CANCELLED");

        assertThat(notificationTypeColumn()).contains("RESERVATION_HOSPITAL_CANCELLED");
        assertThat(notificationTypeColumn()).doesNotContain("RESERVATION_REQUESTED");
        assertThat(migrationMarkerExists()).isFalse();
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
                NotificationReservationRequestedTypeMigrationRunner.MIGRATION_KEY
        );
        return count != null && count == 1;
    }
}
