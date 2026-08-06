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

/** 기존 예약 테이블에 자동 노쇼 추가 유예 진입 시각 컬럼을 한 번만 적용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.no-show-pending-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationNoShowPendingMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_no_show_pending_v1";
    static final String COLUMN_NAME = "no_show_pending_at";
    private static final String LOCK_NAME = "doctorpet:reservation_no_show_pending_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
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
        if (migrationApplied(connection)) {
            assertColumnExists(connection);
            return;
        }
        if (!columnExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservations
                        add column no_show_pending_at datetime(6) null
                        """);
            }
        }
        assertColumnExists(connection);
        recordMigration(connection);
        log.info("자동 노쇼 추가 유예 컬럼 적용 완료: {}", COLUMN_NAME);
    }

    private void assertColumnExists(Connection connection) throws SQLException {
        if (!columnExists(connection)) {
            throw new IllegalStateException(
                    "reservations.no_show_pending_at 컬럼이 없습니다."
            );
        }
    }

    private boolean columnExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = ?
                """)) {
            statement.setString(1, COLUMN_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
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

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "자동 노쇼 추가 유예 마이그레이션 DB 잠금을 획득하지 못했습니다."
                    );
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("자동 노쇼 추가 유예 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
