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

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.event-unique-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationEventUniqueMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_event_unique_v1";
    private static final String UNIQUE_INDEX_NAME = "uk_reservation_event_type";
    private static final String LOCK_NAME = "doctorpet:reservation_event_unique_v1";
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
            assertUniqueConstraintExists(connection);
            return;
        }

        int deletedCount;
        try (Statement statement = connection.createStatement()) {
            deletedCount = statement.executeUpdate("""
                    delete duplicate_event
                      from reservation_events duplicate_event
                      join reservation_events original_event
                        on duplicate_event.reservation_id = original_event.reservation_id
                       and duplicate_event.event_type = original_event.event_type
                       and duplicate_event.id > original_event.id
                    """);
        }

        if (!uniqueConstraintExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservation_events
                        add constraint uk_reservation_event_type
                        unique (reservation_id, event_type)
                        """);
            }
        }

        assertUniqueConstraintExists(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, MIGRATION_KEY);
            statement.executeUpdate();
        }

        log.info(
                "reservation_events UNIQUE 마이그레이션 완료: 중복 이력 {}건을 정리하고 {} 제약을 확인했습니다.",
                deletedCount,
                UNIQUE_INDEX_NAME
        );
    }

    private void acquireMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select get_lock(?, ?)"
        )) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "reservation_events 마이그레이션 DB 잠금을 획득하지 못했습니다."
                    );
                }
            }
        }
    }

    private void releaseMigrationLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select release_lock(?)"
        )) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("reservation_events 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
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

    private void assertUniqueConstraintExists(Connection connection) throws SQLException {
        if (!uniqueConstraintExists(connection)) {
            throw new IllegalStateException(
                    "reservation_events의 (reservation_id, event_type) UNIQUE 제약이 없습니다."
            );
        }
    }

    private boolean uniqueConstraintExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }
}
