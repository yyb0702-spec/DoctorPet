package com.doctorpet.domain.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.migration.ReservationHospitalCanceledStatusMigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — VARCHAR 전환 뒤에도 바로 이전 호환 버전의 모든 기존 ENUM 러너가 재기동을 막거나
 * VARCHAR를 ENUM으로 되돌리지 않는지 검증한다. 이 호환 버전을 먼저 배포한 뒤에만 #199 전환을 한다.
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
        "jwt.secret=doctorpet-notification-varchar-compat-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class NotificationVarcharRollbackCompatibilityIntegrationTest {

    private static final String FUTURE_TYPE = "FUTURE_NOTIFICATION_TYPE";
    private static final String RESERVATION_MIGRATION_KEY =
            "reservation_hospital_canceled_status_v4";
    private static final String CURRENT_NOTIFICATION_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED',"
                    + "'RESERVATION_REQUESTED','RESERVATION_WAITLIST_OFFERED')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreNotificationType() {
        jdbcTemplate.update("delete from notifications where type = ?", FUTURE_TYPE);
        jdbcTemplate.execute(
                "alter table notifications modify column type " + CURRENT_NOTIFICATION_ENUM + " not null"
        );
        new NotificationWaitlistOfferedTypeMigrationRunner(jdbcTemplate).run(null);
        new NotificationReservationRequestedTypeMigrationRunner(jdbcTemplate).run(null);
        new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate).run(null);
    }

    @Test
    @DisplayName("VARCHAR 전환 뒤 호환 버전의 기존 ENUM 러너를 재실행해도 새 값을 보존하며 부팅할 수 있다")
    void legacyRunnersKeepVarcharAndUnknownValuesAfterConversion() {
        jdbcTemplate.execute("alter table notifications modify column type varchar(40) not null");
        long memberId = Math.abs(System.nanoTime());
        jdbcTemplate.update("""
                insert into notifications
                       (member_id, recipient_type, recipient_id, type, content,
                        resource_type, resource_id, created_at, updated_at)
                values (?, 'MEMBER', ?, ?, '미래 버전 알림', 'RESERVATION', 991, now(6), now(6))
                """, memberId, memberId, FUTURE_TYPE);
        jdbcTemplate.update("delete from schema_migrations where migration_key in (?, ?, ?)",
                NotificationWaitlistOfferedTypeMigrationRunner.MIGRATION_KEY,
                NotificationReservationRequestedTypeMigrationRunner.MIGRATION_KEY,
                RESERVATION_MIGRATION_KEY);

        NotificationWaitlistOfferedTypeMigrationRunner waitlistRunner =
                new NotificationWaitlistOfferedTypeMigrationRunner(jdbcTemplate);
        NotificationReservationRequestedTypeMigrationRunner requestedRunner =
                new NotificationReservationRequestedTypeMigrationRunner(jdbcTemplate);
        ReservationHospitalCanceledStatusMigrationRunner reservationRunner =
                new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate);

        // ApplicationRunner 재실행은 롤백한 이전 색 컨테이너가 부팅할 때의 경로와 같다.
        waitlistRunner.run(null);
        requestedRunner.run(null);
        reservationRunner.run(null);
        waitlistRunner.run(null);
        requestedRunner.run(null);
        reservationRunner.run(null);

        assertThat(notificationTypeColumn()).isEqualTo("varchar(40)");
        assertThat(jdbcTemplate.queryForObject(
                "select type from notifications where type = ?", String.class, FUTURE_TYPE
        )).isEqualTo(FUTURE_TYPE);
        assertThat(migrationMarkerCount()).isEqualTo(3);
    }

    private String notificationTypeColumn() {
        return jdbcTemplate.queryForObject("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = 'type'
                """, String.class);
    }

    private Integer migrationMarkerCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from schema_migrations
                 where migration_key in (?, ?, ?)
                """, Integer.class,
                NotificationWaitlistOfferedTypeMigrationRunner.MIGRATION_KEY,
                NotificationReservationRequestedTypeMigrationRunner.MIGRATION_KEY,
                RESERVATION_MIGRATION_KEY);
    }
}
