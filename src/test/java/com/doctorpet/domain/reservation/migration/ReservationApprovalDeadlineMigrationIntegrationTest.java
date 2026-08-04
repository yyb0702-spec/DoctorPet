package com.doctorpet.domain.reservation.migration;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.LocalDateTime;
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

/** Level 3 — 실제 MySQL에서 기존 예약 백필과 DDL 적용·다중 기동을 검증한다. */
@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "reservation.event-unique-migration.enabled=false",
        "reservation.approval-deadline-migration.enabled=false",
        "reservation.approval-timeout.initial-delay-ms=3600000"
})
class ReservationApprovalDeadlineMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    private Long reservationId;
    private Long slotId;

    @BeforeEach
    void prepareSchema() {
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationApprovalDeadlineMigrationRunner.MIGRATION_KEY
        );
        dropApprovalDeadlineIndex();
        makeApprovalDeadlineNullable();
    }

    @AfterEach
    void restoreSchema() {
        if (reservationId != null) {
            jdbcTemplate.update("delete from reservations where id = ?", reservationId);
        }
        if (slotId != null) {
            jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
        }
        backfillAllReservations();
        makeApprovalDeadlineNotNull();
        createApprovalDeadlineIndex();
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationApprovalDeadlineMigrationRunner.MIGRATION_KEY
        );
    }

    @Test
    @DisplayName("기존 예약을 공식으로 다시 계산한 뒤 NOT NULL과 조회 인덱스를 적용한다")
    void existingReservations_areBackfilledBeforeConstraints() {
        TestReservation data = saveReservation();
        jdbcTemplate.update(
                "update reservations set approval_deadline_at = ? where id = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                data.reservationId()
        );

        new ReservationApprovalDeadlineMigrationRunner(jdbcTemplate).run(null);

        LocalDateTime actual = jdbcTemplate.queryForObject(
                "select approval_deadline_at from reservations where id = ?",
                LocalDateTime.class,
                data.reservationId()
        );
        assertThat(actual).isEqualTo(data.expectedDeadline());
        assertThat(approvalDeadlineNullable()).isFalse();
        assertThat(approvalDeadlineIndexExists()).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
    }

    @Test
    @DisplayName("마커가 있는데 NOT NULL 또는 인덱스가 없으면 부팅 오류로 드러낸다")
    void appliedMarkerWithoutTargetSchema_failsFast() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, ReservationApprovalDeadlineMigrationRunner.MIGRATION_KEY);

        assertThatThrownBy(
                () -> new ReservationApprovalDeadlineMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT NULL 제약이 없습니다");
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 최초 기동해도 DB 잠금으로 한 번만 적용한다")
    void concurrentRunners_areSerializedByDatabaseLock() throws InterruptedException {
        TestReservation data = saveReservation();
        jdbcTemplate.update(
                "update reservations set approval_deadline_at = null where id = ?",
                data.reservationId()
        );
        ReservationApprovalDeadlineMigrationRunner runner =
                new ReservationApprovalDeadlineMigrationRunner(jdbcTemplate);
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
        assertThat(approvalDeadlineNullable()).isFalse();
        assertThat(approvalDeadlineIndexExists()).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select approval_deadline_at from reservations where id = ?",
                LocalDateTime.class,
                data.reservationId()
        )).isEqualTo(data.expectedDeadline());
    }

    private TestReservation saveReservation() {
        long hospitalId = Math.abs(System.nanoTime());
        LocalDateTime requestedAt = LocalDateTime.now(SEOUL_ZONE_ID)
                .withNano(0)
                .minusMinutes(10);
        LocalDateTime slotStartAt = requestedAt.plusHours(8);
        LocalDateTime expectedDeadline = requestedAt.plusHours(1);

        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                slotStartAt,
                slotStartAt.plusMinutes(30)
        );
        slot.reserve();
        slot = slotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Reservation reservation = Reservation.request(
                hospitalId + 1,
                1L,
                hospitalId,
                slot.getId(),
                1L,
                "초코",
                "DOG",
                requestedAt,
                slotStartAt
        );
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();
        return new TestReservation(reservationId, expectedDeadline);
    }

    private void backfillAllReservations() {
        jdbcTemplate.update("""
                update reservations reservation
                  join reservation_slots slot on slot.id = reservation.slot_id
                   set reservation.approval_deadline_at = least(
                           date_add(reservation.requested_at, interval 1 hour),
                           date_sub(slot.start_at, interval 2 hour)
                       )
                 where reservation.approval_deadline_at is null
                """);
    }

    private void makeApprovalDeadlineNullable() {
        jdbcTemplate.execute("""
                alter table reservations
                modify column approval_deadline_at datetime(6) null
                """);
    }

    private void makeApprovalDeadlineNotNull() {
        jdbcTemplate.execute("""
                alter table reservations
                modify column approval_deadline_at datetime(6) not null
                """);
    }

    private boolean approvalDeadlineNullable() {
        String nullable = jdbcTemplate.queryForObject("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = 'approval_deadline_at'
                """, String.class);
        return "YES".equalsIgnoreCase(nullable);
    }

    private void dropApprovalDeadlineIndex() {
        if (approvalDeadlineIndexExists()) {
            jdbcTemplate.execute("""
                    alter table reservations
                    drop index idx_reservations_status_approval_deadline
                    """);
        }
    }

    private void createApprovalDeadlineIndex() {
        if (!approvalDeadlineIndexExists()) {
            jdbcTemplate.execute("""
                    create index idx_reservations_status_approval_deadline
                    on reservations (status, approval_deadline_at)
                    """);
        }
    }

    private boolean approvalDeadlineIndexExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = 'reservations'
                           and index_name = ?
                         group by index_name
                        having group_concat(column_name order by seq_in_index)
                               = 'status,approval_deadline_at'
                       ) matching_index
                """, Integer.class, ReservationApprovalDeadlineMigrationRunner.INDEX_NAME);
        return count != null && count > 0;
    }

    private boolean migrationMarkerExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class, ReservationApprovalDeadlineMigrationRunner.MIGRATION_KEY);
        return count != null && count > 0;
    }

    private record TestReservation(Long reservationId, LocalDateTime expectedDeadline) {
    }
}
