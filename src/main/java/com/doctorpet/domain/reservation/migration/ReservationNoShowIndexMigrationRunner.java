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

/** 자동 노쇼 대상 조회용 인덱스를 운영 DB에 한 번만 적용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.no-show-index-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationNoShowIndexMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_no_show_index_v1";
    static final String INDEX_NAME = "idx_reservations_status_slot";
    private static final String LOCK_NAME = "doctorpet:reservation_no_show_index_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            acquireMigrationLock(connection);
            try {
                migrate(connection);
            } finally {
                releaseMigrationLock(connection);
            }
            return null;
        });
    }

    private void migrate(Connection connection) throws SQLException {
        if (migrationApplied(connection)) {
            assertTargetSchema(connection);
            return;
        }

        if (!indexExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        create index idx_reservations_status_slot
                        on reservations (status, slot_id)
                        """);
            }
        }

        assertTargetSchema(connection);
        recordMigration(connection);
        log.info("자동 노쇼 조회 인덱스 적용 완료: {}", INDEX_NAME);
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (!indexExists(connection)) {
            throw new IllegalStateException("reservations의 자동 노쇼 조회 인덱스가 없습니다.");
        }
    }

    private boolean indexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = 'reservations'
                           and index_name = ?
                         group by index_name
                        having group_concat(column_name order by seq_in_index) = 'status,slot_id'
                       ) matching_index
                """)) {
            statement.setString(1, INDEX_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean migrationApplied(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
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
                values (?, now())
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, MIGRATION_KEY);
            statement.executeUpdate();
        }
    }

    private void acquireMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException("자동 노쇼 인덱스 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseMigrationLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("자동 노쇼 인덱스 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
