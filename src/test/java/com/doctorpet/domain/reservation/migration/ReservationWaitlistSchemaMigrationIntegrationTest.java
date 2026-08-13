package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL에서 대기열 DDL·인덱스·마커 재실행을 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3307/doctorpet?serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
        "spring.datasource.username=root",
        "spring.datasource.password=root",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=localhost",
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "jwt.secret=doctorpet-waitlist-migration-test-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class ReservationWaitlistSchemaMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreMigrationState() {
        ReservationWaitlistSchemaMigrationRunner runner =
                new ReservationWaitlistSchemaMigrationRunner(jdbcTemplate);
        runner.migrateBeforeJpa();
    }

    @Test
    @DisplayName("대기열 스키마는 실제 MySQL에 생성되고 재실행해도 UNIQUE·FIFO 인덱스를 보존한다")
    void migration_createsSchemaAndIsIdempotent() {
        jdbcTemplate.execute("drop table if exists reservation_waitlists");
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationWaitlistSchemaMigrationRunner.MIGRATION_KEY
        );
        ReservationWaitlistSchemaMigrationRunner runner =
                new ReservationWaitlistSchemaMigrationRunner(jdbcTemplate);

        runner.migrateBeforeJpa();
        runner.run(null);

        assertThat(tableExists()).isTrue();
        assertThat(columnExists("created_at")).isTrue();
        assertThat(columnExists("version")).isTrue();
        assertThat(indexExists("uk_reservation_waitlists_member_slot")).isTrue();
        assertThat(indexExists("idx_reservation_waitlists_slot_status_created")).isTrue();
        assertThat(migrationMarkerExists()).isTrue();
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema = database() and table_name = 'reservation_waitlists'
                """, Integer.class);
        return count != null && count == 1;
    }

    private boolean columnExists(String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_waitlists'
                   and column_name = ?
                """, Integer.class, columnName);
        return count != null && count == 1;
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'reservation_waitlists'
                   and index_name = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }

    private boolean migrationMarkerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                ReservationWaitlistSchemaMigrationRunner.MIGRATION_KEY
        );
        return count != null && count == 1;
    }
}
