package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL에서 병원 취소 상태명 백필·PAYMENT_PENDING 보존·재실행 멱등성을 검증한다. */
@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class ReservationHospitalCanceledStatusMigrationIntegrationTest {

    private static final String LEGACY_STATUS_ENUM =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','HOSPITAL_CANCELLED',"
                    + "'HOSPITAL_CANCELED','IN_TREATMENT','NO_SHOW','NO_SHOW_PENDING',"
                    + "'REJECTED','REQUESTED','TREATMENT_COMPLETED')";
    private static final String FINAL_STATUS_ENUM =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','HOSPITAL_CANCELED',"
                    + "'IN_TREATMENT','NO_SHOW','NO_SHOW_PENDING','REJECTED',"
                    + "'REQUESTED','TREATMENT_COMPLETED')";
    private static final String LEGACY_EVENT_ENUM =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','HOSPITAL_CANCELLED',"
                    + "'HOSPITAL_CANCELED','MANUAL_NO_SHOW','NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";
    private static final String FINAL_EVENT_ENUM =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','HOSPITAL_CANCELED',"
                    + "'MANUAL_NO_SHOW','NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";

    private static final String LEGACY_NOTIFICATION_ENUM = "enum('NO_SHOW','PAYMENT_PENDING',"
            + "'PAYMENT_RESULT','RESERVATION_CONFIRMED',"
            + "'RESERVATION_HOSPITAL_CANCELLED','RESERVATION_HOSPITAL_CANCELED',"
            + "'RESERVATION_REJECTED')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    private Long legacyNotificationId;
    private Long reservationId;
    private Long slotId;

    @BeforeEach
    void prepareLegacySchema() {
        deleteMigrationMarker();
        jdbcTemplate.execute(
                "alter table notifications modify column type "
                        + LEGACY_NOTIFICATION_ENUM + " not null"
        );
        jdbcTemplate.execute(
                "alter table reservations modify column status "
                        + LEGACY_STATUS_ENUM + " not null"
        );
        jdbcTemplate.execute(
                "alter table reservation_events modify column event_type "
                        + LEGACY_EVENT_ENUM + " not null"
        );
    }

    @AfterEach
    void restoreSchema() {
        if (legacyNotificationId != null) {
            jdbcTemplate.update("delete from notifications where id = ?", legacyNotificationId);
        }
        if (reservationId != null) {
            jdbcTemplate.update("delete from reservation_events where reservation_id = ?", reservationId);
            jdbcTemplate.update("delete from reservations where id = ?", reservationId);
        }
        if (slotId != null) {
            jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
        }
        jdbcTemplate.update("""
                update reservations set status = 'HOSPITAL_CANCELED'
                 where status = 'HOSPITAL_CANCELLED'
                """);
        jdbcTemplate.update("""
                update reservation_events set event_type = 'HOSPITAL_CANCELED'
                 where event_type = 'HOSPITAL_CANCELLED'
                """);
        jdbcTemplate.update("""
                update notifications set type = 'RESERVATION_HOSPITAL_CANCELED'
                 where type = 'RESERVATION_HOSPITAL_CANCELLED'
                """);
        jdbcTemplate.execute(
                "alter table reservations modify column status "
                        + FINAL_STATUS_ENUM + " not null"
        );
        jdbcTemplate.execute(
                "alter table reservation_events modify column event_type "
                        + FINAL_EVENT_ENUM + " not null"
        );
        jdbcTemplate.execute(
                "alter table notifications modify column type "
                        + "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT',"
                        + "'RESERVATION_CONFIRMED','RESERVATION_HOSPITAL_CANCELED',"
                        + "'RESERVATION_REJECTED') not null"
        );
        deleteMigrationMarker();
    }

    @Test
    @DisplayName("JPA 초기화 전 기존 병원 취소 상태·이력·알림을 CANCELED로 백필하고 PAYMENT_PENDING을 보존한다")
    void preJpaMigration_preservesLegacyHistoryAndPaymentPending() {
        insertLegacyHospitalCanceledReservationAndEvent();
        insertLegacyHospitalCanceledNotification();

        ReservationHospitalCanceledStatusMigrationRunner runner =
                new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate);
        runner.migrateBeforeJpa();

        assertThat(reservationStatus()).isEqualTo("HOSPITAL_CANCELED");
        assertThat(eventType()).isEqualTo("HOSPITAL_CANCELED");
        assertThat(notificationType()).isEqualTo("RESERVATION_HOSPITAL_CANCELED");
        assertThat(notificationEnum()).contains("PAYMENT_PENDING");
        assertThat(notificationEnum()).contains("RESERVATION_HOSPITAL_CANCELED");
        assertThat(notificationEnum()).doesNotContain("RESERVATION_HOSPITAL_CANCELLED");
        assertThat(columnExists("hospital_cancel_reason")).isTrue();
        assertThat(columnExists("hospital_canceled_at")).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
    }

    @Test
    @DisplayName("마이그레이션을 재실행해도 데이터와 스키마가 중복 변경되지 않는다")
    void rerun_isIdempotent() {
        insertLegacyHospitalCanceledNotification();
        ReservationHospitalCanceledStatusMigrationRunner runner =
                new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate);

        runner.run(null);
        runner.run(null);

        assertThat(notificationType()).isEqualTo("RESERVATION_HOSPITAL_CANCELED");
        assertThat(notificationEnum()).doesNotContain("RESERVATION_HOSPITAL_CANCELLED");
        assertThat(migrationMarkerExists()).isTrue();
    }

    private void insertLegacyHospitalCanceledNotification() {
        long memberId = Math.abs(System.nanoTime());
        jdbcTemplate.update("""
                insert into notifications
                       (member_id, recipient_type, recipient_id, type, content,
                        resource_type, resource_id, created_at, updated_at)
                values (?, 'MEMBER', ?, 'RESERVATION_HOSPITAL_CANCELLED',
                        '병원 사정으로 예약이 취소되었습니다.', 'RESERVATION', 991, now(6), now(6))
                """, memberId, memberId);
        legacyNotificationId = jdbcTemplate.queryForObject(
                "select id from notifications where member_id = ? order by id desc limit 1",
                Long.class,
                memberId
        );
    }

    private void insertLegacyHospitalCanceledReservationAndEvent() {
        long hospitalId = Math.abs(System.nanoTime());
        LocalDateTime startAt = LocalDateTime.now().plusDays(1).withNano(0);
        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                startAt,
                startAt.plusMinutes(30)
        );
        slot.reserve();
        slot = slotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Reservation reservation = Reservation.request(
                hospitalId + 1,
                1L,
                hospitalId,
                slotId,
                1L,
                "초코",
                "DOG",
                LocalDateTime.now().withNano(0),
                startAt
        );
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();

        jdbcTemplate.update(
                "update reservations set status = 'HOSPITAL_CANCELLED' where id = ?",
                reservationId
        );
        jdbcTemplate.update("""
                insert into reservation_events
                       (reservation_id, event_type, memo, processed_by, occurred_at)
                values (?, 'HOSPITAL_CANCELLED', '응급수술', 77, now(6))
                """, reservationId);
    }

    private String reservationStatus() {
        return jdbcTemplate.queryForObject(
                "select status from reservations where id = ?",
                String.class,
                reservationId
        );
    }

    private String eventType() {
        return jdbcTemplate.queryForObject(
                "select event_type from reservation_events where reservation_id = ?",
                String.class,
                reservationId
        );
    }

    private String notificationType() {
        return jdbcTemplate.queryForObject(
                "select type from notifications where id = ?",
                String.class,
                legacyNotificationId
        );
    }

    private String notificationEnum() {
        return jdbcTemplate.queryForObject("""
                select column_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = 'type'
                """, String.class);
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = ?
                """, Integer.class, columnName);
        return count != null && count == 1;
    }

    private boolean migrationMarkerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                ReservationHospitalCanceledStatusMigrationRunner.MIGRATION_KEY
        );
        return count != null && count == 1;
    }

    private void deleteMigrationMarker() {
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationHospitalCanceledStatusMigrationRunner.MIGRATION_KEY
        );
    }
}
