package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL에서 자동 노쇼 조회 인덱스 마이그레이션 경로를 검증한다. */
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
        "reservation.approval-timeout.initial-delay-ms=3600000",
        "reservation.no-show-index-migration.enabled=false"
})
class ReservationNoShowIndexMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareSchema() {
        deleteMarker();
        dropIndex();
    }

    @AfterEach
    void restoreSchema() {
        dropIndex();
        createIndex();
        deleteMarker();
    }

    @Test
    @DisplayName("인덱스와 마커가 없으면 자동 노쇼 조회 인덱스를 만들고 마커를 기록한다")
    void missingIndexAndMarker_areCreatedTogether() {
        new ReservationNoShowIndexMigrationRunner(jdbcTemplate).run(null);

        assertThat(indexExists()).isTrue();
        assertThat(markerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 적용된 마이그레이션을 다시 실행해도 인덱스와 마커가 중복되지 않는다")
    void rerun_isIdempotent() {
        ReservationNoShowIndexMigrationRunner runner =
                new ReservationNoShowIndexMigrationRunner(jdbcTemplate);

        runner.run(null);
        runner.run(null);

        assertThat(indexExists()).isTrue();
        assertThat(markerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("마커만 남고 인덱스가 없으면 운영 스키마 불일치를 즉시 드러낸다")
    void markerWithoutIndex_failsFast() {
        insertMarker();

        assertThatThrownBy(
                () -> new ReservationNoShowIndexMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("자동 노쇼 조회 인덱스가 없습니다");
    }

    @Test
    @DisplayName("이름만 같은 잘못된 인덱스가 있으면 구성 불일치로 즉시 실패한다")
    void sameNameWithWrongColumns_failsFast() {
        createWrongIndex();

        assertThatThrownBy(
                () -> new ReservationNoShowIndexMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("자동 노쇼 조회 인덱스 구성이 올바르지 않습니다");
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 실행돼도 인덱스와 마커는 한 번만 남는다")
    void concurrentRunners_areSerializedByDatabaseLock() throws InterruptedException {
        ReservationNoShowIndexMigrationRunner runner =
                new ReservationNoShowIndexMigrationRunner(jdbcTemplate);
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
        assertThat(indexExists()).isTrue();
        assertThat(markerCount()).isEqualTo(1);
    }

    private void insertMarker() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, ReservationNoShowIndexMigrationRunner.MIGRATION_KEY);
    }

    private void deleteMarker() {
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationNoShowIndexMigrationRunner.MIGRATION_KEY
        );
    }

    private int markerCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class, ReservationNoShowIndexMigrationRunner.MIGRATION_KEY);
        return count == null ? 0 : count;
    }

    private void dropIndex() {
        if (indexNameExists()) {
            jdbcTemplate.execute("""
                    alter table reservations
                    drop index idx_reservations_status_slot
                    """);
        }
    }

    private void createIndex() {
        if (!indexExists()) {
            jdbcTemplate.execute("""
                    create index idx_reservations_status_slot
                    on reservations (status, slot_id)
                    """);
        }
    }

    private boolean indexExists() {
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
                               = 'status,slot_id'
                       ) matching_index
                """, Integer.class, ReservationNoShowIndexMigrationRunner.INDEX_NAME);
        return count != null && count > 0;
    }

    private void createWrongIndex() {
        jdbcTemplate.execute("""
                create index idx_reservations_status_slot
                on reservations (slot_id, status)
                """);
    }

    private boolean indexNameExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'reservations'
                   and index_name = ?
                """, Integer.class, ReservationNoShowIndexMigrationRunner.INDEX_NAME);
        return count != null && count > 0;
    }
}
