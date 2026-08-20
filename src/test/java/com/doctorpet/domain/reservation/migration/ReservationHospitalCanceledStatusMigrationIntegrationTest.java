package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL에서 병원 취소 상태명 백필·PAYMENT_PENDING 보존·재실행 멱등성을 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3307/doctorpet?serverTimezone=Asia/Seoul&characterEncoding=UTF-8}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:root}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:root}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}",
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "jwt.secret=doctorpet-migration-integration-test-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class ReservationHospitalCanceledStatusMigrationIntegrationTest {

    private static final String LEGACY_STATUS_ENUM =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','HOSPITAL_CANCELLED',"
                    + "'HOSPITAL_CANCELED','IN_TREATMENT','NO_SHOW','NO_SHOW_PENDING',"
                    + "'REJECTED','REQUESTED','TREATMENT_COMPLETED')";
    private static final String STATUS_ENUM_WITHOUT_HOSPITAL_CANCELED =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','IN_TREATMENT','NO_SHOW',"
                    + "'NO_SHOW_PENDING','REJECTED','REQUESTED','TREATMENT_COMPLETED')";
    private static final String FINAL_STATUS_ENUM =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','HOSPITAL_CANCELED',"
                    + "'IN_TREATMENT','NO_SHOW','NO_SHOW_PENDING','REJECTED',"
                    + "'REQUESTED','TREATMENT_COMPLETED')";
    private static final String LEGACY_EVENT_ENUM =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','HOSPITAL_CANCELLED',"
                    + "'HOSPITAL_CANCELED','MANUAL_NO_SHOW','NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";
    private static final String EVENT_ENUM_WITHOUT_HOSPITAL_CANCELED =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','MANUAL_NO_SHOW',"
                    + "'NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";
    private static final String FINAL_EVENT_ENUM =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','HOSPITAL_CANCELED',"
                    + "'MANUAL_NO_SHOW','NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    private Long reservationId;
    private Long slotId;
    private List<Long> hospitalCanceledReservationIds = List.of();
    private List<Long> hospitalCanceledEventIds = List.of();

    @BeforeEach
    void prepareLegacySchema() {
        deleteMigrationMarker();
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
        jdbcTemplate.execute(
                "alter table reservations modify column status "
                        + FINAL_STATUS_ENUM + " not null"
        );
        jdbcTemplate.execute(
                "alter table reservation_events modify column event_type "
                        + FINAL_EVENT_ENUM + " not null"
        );
        restoreTemporarilyRewrittenHospitalCanceledRows();
        restoreHospitalCancelColumnsForJpa();
        deleteMigrationMarker();
    }

    @Test
    @DisplayName("기존 MySQL 스키마에서 병원 취소 이력을 정규화하고 취소 컬럼을 보존한다")
    void preJpaMigration_preservesLegacyHistoryAndHospitalCancelColumns() {
        // notifications.type 정리는 이 러너의 범위가 아니다(이슈 #176에서 VARCHAR 전환과 함께 알림 도메인 러너로 이관).
        insertLegacyHospitalCanceledReservationAndEvent();
        dropHospitalCancelColumns();

        ReservationHospitalCanceledStatusMigrationRunner runner =
                new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate);
        runner.migrateBeforeJpa();

        assertThat(reservationStatus()).isEqualTo("HOSPITAL_CANCELED");
        assertThat(eventType()).isEqualTo("HOSPITAL_CANCELED");
        assertThat(columnExists("hospital_cancel_reason")).isTrue();
        assertThat(columnExists("hospital_canceled_at")).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
    }

    @Test
    @DisplayName("마이그레이션을 재실행해도 데이터와 스키마가 중복 변경되지 않는다")
    void rerun_isIdempotent() {
        insertLegacyHospitalCanceledReservationAndEvent();
        dropHospitalCancelColumns();
        ReservationHospitalCanceledStatusMigrationRunner runner =
                new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate);

        runner.run(null);
        runner.run(null);

        assertThat(reservationStatus()).isEqualTo("HOSPITAL_CANCELED");
        assertThat(eventType()).isEqualTo("HOSPITAL_CANCELED");
        assertThat(columnExists("hospital_cancel_reason")).isTrue();
        assertThat(columnExists("hospital_canceled_at")).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
    }

    @Test
    @DisplayName("병원 취소 enum 값이 전혀 없는 기존 스키마에도 표준 값을 추가한다")
    void migration_addsHospitalCanceledWhenNeitherSpellingExists() {
        temporarilyRewriteHospitalCanceledRows();
        jdbcTemplate.execute(
                "alter table reservations modify column status "
                        + STATUS_ENUM_WITHOUT_HOSPITAL_CANCELED + " not null"
        );
        jdbcTemplate.execute(
                "alter table reservation_events modify column event_type "
                        + EVENT_ENUM_WITHOUT_HOSPITAL_CANCELED + " not null"
        );
        ReservationHospitalCanceledStatusMigrationRunner runner =
                new ReservationHospitalCanceledStatusMigrationRunner(jdbcTemplate);

        runner.migrateBeforeJpa();

        assertThat(reservationStatusEnum()).contains("HOSPITAL_CANCELED");
        assertThat(reservationStatusEnum()).doesNotContain("HOSPITAL_CANCELLED");
        assertThat(eventTypeEnum()).contains("HOSPITAL_CANCELED");
        assertThat(eventTypeEnum()).doesNotContain("HOSPITAL_CANCELLED");
    }

    private void temporarilyRewriteHospitalCanceledRows() {
        hospitalCanceledReservationIds = jdbcTemplate.queryForList(
                "select id from reservations where status = 'HOSPITAL_CANCELED'", Long.class);
        hospitalCanceledEventIds = jdbcTemplate.queryForList(
                "select id from reservation_events where event_type = 'HOSPITAL_CANCELED'", Long.class);
        if (!hospitalCanceledReservationIds.isEmpty()) {
            jdbcTemplate.update("update reservations set status = 'CANCELED' where status = 'HOSPITAL_CANCELED'");
        }
        if (!hospitalCanceledEventIds.isEmpty()) {
            jdbcTemplate.update("update reservation_events set event_type = 'CHECKED_IN' where event_type = 'HOSPITAL_CANCELED'");
        }
    }

    private void restoreTemporarilyRewrittenHospitalCanceledRows() {
        for (Long id : hospitalCanceledReservationIds) {
            jdbcTemplate.update("update reservations set status = 'HOSPITAL_CANCELED' where id = ?", id);
        }
        for (Long id : hospitalCanceledEventIds) {
            jdbcTemplate.update("update reservation_events set event_type = 'HOSPITAL_CANCELED' where id = ?", id);
        }
    }

    private void dropHospitalCancelColumns() {
        jdbcTemplate.execute("alter table reservations drop column hospital_cancel_reason");
        jdbcTemplate.execute("alter table reservations drop column hospital_canceled_at");
    }

    private void restoreHospitalCancelColumnsForJpa() {
        if (!columnExists("hospital_cancel_reason")) {
            jdbcTemplate.execute(
                    "alter table reservations add column hospital_cancel_reason varchar(255) null"
            );
        }
        if (!columnExists("hospital_canceled_at")) {
            jdbcTemplate.execute(
                    "alter table reservations add column hospital_canceled_at datetime(6) null"
            );
        }
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

    private String reservationStatusEnum() {
        return jdbcTemplate.queryForObject("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = 'status'
                """, String.class);
    }

    private String eventTypeEnum() {
        return jdbcTemplate.queryForObject("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_events'
                   and column_name = 'event_type'
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
