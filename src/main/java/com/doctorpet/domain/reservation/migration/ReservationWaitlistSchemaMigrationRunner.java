package com.doctorpet.domain.reservation.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 대기열 테이블과 FIFO 조회 인덱스를 Hibernate 초기화보다 먼저 생성·검증한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.waitlist-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationWaitlistSchemaMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_waitlist_schema_v1";
    private static final String LOCK_NAME = "doctorpet:reservation_waitlist_schema_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        executeMigration();
    }

    void migrateBeforeJpa() {
        executeMigration();
    }

    private void executeMigration() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ensureSchemaMigrationsTable(connection);
            acquireLock(connection);
            try {
                migrate(connection);
            } finally {
                releaseLock(connection);
            }
            return null;
        });
    }

    private void migrate(Connection connection) throws SQLException {
        if (!migrationApplied(connection)) {
            createWaitlistTableIfMissing(connection);
            assertTargetSchema(connection);
            recordMigration(connection);
            log.info("예약 대기열 스키마 마이그레이션 완료");
            return;
        }

        assertTargetSchema(connection);
    }

    private void ensureSchemaMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists schema_migrations (
                        migration_key varchar(100) not null primary key,
                        applied_at datetime(6) not null
                    )
                    """);
        }
    }

    private void createWaitlistTableIfMissing(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists reservation_waitlists (
                        id bigint not null auto_increment,
                        member_id bigint not null,
                        slot_id bigint not null,
                        status enum('WAITING','OFFERED','ACCEPTED','REJECTED','EXPIRED','CANCELED') not null,
                        offered_at datetime(6) null,
                        offer_expires_at datetime(6) null,
                        responded_at datetime(6) null,
                        canceled_at datetime(6) null,
                        created_at datetime(6) not null,
                        updated_at datetime(6) not null,
                        version bigint not null,
                        primary key (id),
                        constraint uk_reservation_waitlists_member_slot unique (member_id, slot_id),
                        key idx_reservation_waitlists_slot_status_created (slot_id, status, created_at, id)
                    )
                    """);
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (!tableExists(connection)) {
            throw new IllegalStateException("reservation_waitlists 테이블이 없습니다.");
        }
        if (!columnExists(connection, "created_at") || !columnExists(connection, "version")) {
            throw new IllegalStateException("reservation_waitlists FIFO·낙관적 락 컬럼이 없습니다.");
        }
        if (!indexExists(connection, "uk_reservation_waitlists_member_slot")
                || !indexExists(connection, "idx_reservation_waitlists_slot_status_created")) {
            throw new IllegalStateException("reservation_waitlists 필수 UNIQUE 또는 FIFO 인덱스가 없습니다.");
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema = database() and table_name = 'reservation_waitlists'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean columnExists(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_waitlists'
                   and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'reservation_waitlists'
                   and index_name = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean migrationApplied(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from schema_migrations where migration_key = ?
                """)) {
            statement.setString(1, MIGRATION_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void recordMigration(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now(6))
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, MIGRATION_KEY);
            statement.executeUpdate();
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException("예약 대기열 스키마 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("예약 대기열 스키마 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
