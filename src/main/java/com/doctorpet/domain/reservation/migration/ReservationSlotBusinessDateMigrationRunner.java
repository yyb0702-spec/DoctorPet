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
        prefix = "reservation.slot-business-date-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationSlotBusinessDateMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_slot_business_date_v1";
    private static final String LOCK_NAME = "doctorpet:reservation_slot_business_date_v1";
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
            assertTargetSchema(connection);
            return;
        }

        addColumnIfMissing(connection);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    update reservation_slots
                       set business_date = date(start_at)
                     where business_date is null
                    """);
        }
        applyNotNull(connection);
        assertTargetSchema(connection);
        recordMigration(connection);
        log.info("예약 슬롯 영업 기준일 백필 및 NOT NULL 적용 완료");
    }

    private void addColumnIfMissing(Connection connection) throws SQLException {
        if (!columnExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservation_slots
                        add column business_date date null
                        """);
            }
        }
    }

    private void applyNotNull(Connection connection) throws SQLException {
        if (columnNullable(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservation_slots
                        modify column business_date date not null
                        """);
            }
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (!columnExists(connection) || columnNullable(connection)) {
            throw new IllegalStateException(
                    "reservation_slots.business_date NOT NULL 컬럼이 없습니다."
            );
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                       from reservation_slots
                      where business_date is null
                     """)) {
            if (!resultSet.next() || resultSet.getInt(1) > 0) {
                throw new IllegalStateException("영업 기준일이 없는 예약 슬롯이 있습니다.");
            }
        }
    }

    private boolean columnExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_slots'
                   and column_name = 'business_date'
                   and data_type = 'date'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean columnNullable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_slots'
                   and column_name = 'business_date'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "reservation_slots.business_date 컬럼이 없습니다."
                    );
                }
                return "YES".equalsIgnoreCase(resultSet.getString(1));
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
                            "예약 슬롯 영업 기준일 마이그레이션 잠금을 획득하지 못했습니다."
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
            log.warn("예약 슬롯 영업 기준일 마이그레이션 잠금 해제에 실패했습니다.", exception);
        }
    }
}
