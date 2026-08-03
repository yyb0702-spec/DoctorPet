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

@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "reservation.event-unique-migration.enabled=false"
})
class ReservationEventUniqueMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    private Long reservationId;
    private Long slotId;

    @BeforeEach
    void prepareSchema() {
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationEventUniqueMigrationRunner.MIGRATION_KEY
        );
        ensureUniqueConstraint();
    }

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            jdbcTemplate.update(
                    "delete from reservation_events where reservation_id = ?",
                    reservationId
            );
            jdbcTemplate.update("delete from reservations where id = ?", reservationId);
        }
        if (slotId != null) {
            jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
        }
        jdbcTemplate.update("""
                delete duplicate_event
                  from reservation_events duplicate_event
                  join reservation_events original_event
                    on duplicate_event.reservation_id = original_event.reservation_id
                   and duplicate_event.event_type = original_event.event_type
                   and duplicate_event.id > original_event.id
                """);
        ensureUniqueConstraint();
        dropMigrationTestSupportIndex();
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationEventUniqueMigrationRunner.MIGRATION_KEY
        );
    }

    @Test
    @DisplayName("기존 중복 이력을 최초 한 건으로 정리하고 UNIQUE 제약과 마커를 남긴다")
    void duplicateHistory_isCleanedBeforeAddingUniqueConstraint() throws Exception {
        saveReservation();
        dropUniqueConstraint();
        insertManualNoShowEvent("최초 이력");
        insertManualNoShowEvent("중복 이력");

        new ReservationEventUniqueMigrationRunner(jdbcTemplate).run(null);

        Integer eventCount = jdbcTemplate.queryForObject("""
                select count(*)
                  from reservation_events
                 where reservation_id = ?
                   and event_type = 'MANUAL_NO_SHOW'
                """, Integer.class, reservationId);
        assertThat(eventCount).isEqualTo(1);
        assertThat(uniqueConstraintExists()).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
    }

    @Test
    @DisplayName("마이그레이션 마커가 있는데 UNIQUE 제약이 사라졌으면 부팅 오류로 드러낸다")
    void appliedMarkerWithoutUniqueConstraint_failsFast() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, ReservationEventUniqueMigrationRunner.MIGRATION_KEY);
        dropUniqueConstraint();

        assertThatThrownBy(
                () -> new ReservationEventUniqueMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNIQUE 제약이 없습니다");
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 최초 기동해도 DB 잠금으로 마이그레이션을 한 번만 실행한다")
    void concurrentRunners_areSerializedByDatabaseLock() throws InterruptedException {
        saveReservation();
        dropUniqueConstraint();
        insertManualNoShowEvent("최초 이력");
        insertManualNoShowEvent("중복 이력");

        ReservationEventUniqueMigrationRunner runner =
                new ReservationEventUniqueMigrationRunner(jdbcTemplate);
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
        assertThat(uniqueConstraintExists()).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
        assertThat(manualNoShowEventCount()).isEqualTo(1);
    }

    private void saveReservation() {
        long hospitalId = System.nanoTime();
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID).withNano(0);
        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                now.minusMinutes(20),
                now.plusMinutes(10)
        );
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Reservation reservation = Reservation.request(
                hospitalId + 1,
                1L,
                hospitalId,
                slot.getId(),
                1L,
                "초코",
                "DOG",
                now.minusDays(1)
        );
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();
    }

    private void insertManualNoShowEvent(String memo) {
        jdbcTemplate.update("""
                insert into reservation_events
                    (reservation_id, event_type, memo, processed_by, occurred_at)
                values (?, 'MANUAL_NO_SHOW', ?, 1, now())
                """, reservationId, memo);
    }

    private int manualNoShowEventCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from reservation_events
                 where reservation_id = ?
                   and event_type = 'MANUAL_NO_SHOW'
                """, Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    private void ensureUniqueConstraint() {
        if (!uniqueConstraintExists()) {
            jdbcTemplate.execute("""
                    alter table reservation_events
                    add constraint uk_reservation_event_type
                    unique (reservation_id, event_type)
                    """);
        }
    }

    private void dropUniqueConstraint() {
        if (uniqueConstraintExists()) {
            ensureMigrationTestSupportIndex();
            jdbcTemplate.execute("""
                    alter table reservation_events
                    drop index uk_reservation_event_type
                    """);
        }
    }

    private void ensureMigrationTestSupportIndex() {
        if (!indexExists("idx_reservation_events_migration_test")) {
            jdbcTemplate.execute("""
                    create index idx_reservation_events_migration_test
                    on reservation_events (reservation_id)
                    """);
        }
    }

    private void dropMigrationTestSupportIndex() {
        if (indexExists("idx_reservation_events_migration_test")) {
            jdbcTemplate.execute("""
                    alter table reservation_events
                    drop index idx_reservation_events_migration_test
                    """);
        }
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'reservation_events'
                   and index_name = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }

    private boolean uniqueConstraintExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = 'reservation_events'
                           and non_unique = 0
                         group by index_name
                        having group_concat(column_name order by seq_in_index)
                               = 'reservation_id,event_type'
                       ) unique_indexes
                """, Integer.class);
        return count != null && count > 0;
    }

    private boolean migrationMarkerExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class, ReservationEventUniqueMigrationRunner.MIGRATION_KEY);
        return count != null && count > 0;
    }
}
