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

/** 기존 예약의 승인 마감 시각을 백필한 뒤 운영 스키마 제약을 적용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.approval-deadline-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationApprovalDeadlineMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_approval_deadline_v1";
    static final String INDEX_NAME = "idx_reservations_status_approval_deadline";
    private static final String LOCK_NAME = "doctorpet:reservation_approval_deadline_v1";
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

        int updatedCount;
        try (Statement statement = connection.createStatement()) {
            updatedCount = statement.executeUpdate("""
                    update reservations reservation
                      join reservation_slots slot on slot.id = reservation.slot_id
                       set reservation.approval_deadline_at = least(
                               date_add(reservation.requested_at, interval 1 hour),
                               date_sub(slot.start_at, interval 2 hour)
                           )
                    """);
        }

        int missingCount = countMissingDeadlines(connection);
        if (missingCount > 0) {
            throw new IllegalStateException(
                    "approval_deadline_at 백필 누락 예약이 " + missingCount + "건 있습니다."
            );
        }

        applyNotNullConstraint(connection);
        applyLookupIndex(connection);
        assertTargetSchema(connection);
        recordMigration(connection);

        log.info(
                "예약 승인 마감 마이그레이션 완료: {}건 백필, NOT NULL 및 {} 인덱스 확인",
                updatedCount,
                INDEX_NAME
        );
    }

    private void applyNotNullConstraint(Connection connection) throws SQLException {
        if (approvalDeadlineNullable(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservations
                        modify column approval_deadline_at datetime(6) not null
                        """);
            }
        }
    }

    private void applyLookupIndex(Connection connection) throws SQLException {
        if (!indexExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        create index idx_reservations_status_approval_deadline
                        on reservations (status, approval_deadline_at)
                        """);
            }
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (approvalDeadlineNullable(connection)) {
            throw new IllegalStateException(
                    "reservations.approval_deadline_at NOT NULL 제약이 없습니다."
            );
        }
        if (!indexExists(connection)) {
            throw new IllegalStateException(
                    "reservations의 승인 타임아웃 조회 인덱스가 없습니다."
            );
        }
    }

    private int countMissingDeadlines(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                       from reservations
                      where approval_deadline_at is null
                     """)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private boolean approvalDeadlineNullable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = 'approval_deadline_at'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "reservations.approval_deadline_at 컬럼이 없습니다."
                    );
                }
                return "YES".equalsIgnoreCase(resultSet.getString(1));
            }
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
                        having group_concat(column_name order by seq_in_index)
                               = 'status,approval_deadline_at'
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
        try (PreparedStatement statement = connection.prepareStatement(
                "select get_lock(?, ?)"
        )) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "예약 승인 마감 마이그레이션 DB 잠금을 획득하지 못했습니다."
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
            log.warn("예약 승인 마감 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
