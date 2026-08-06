package com.doctorpet.domain.hospital.migration;

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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnProperty(
        prefix = "hospital.capability-value-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HospitalCapabilityValueMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "hospital_capability_value_varchar_v1";
    private static final String LOCK_NAME =
            "doctorpet:hospital_capability_value_varchar_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final int TARGET_LENGTH = 32;

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
            assertTargetColumn(connection);
            return;
        }

        assertExistingValuesFit(connection);
        if (!targetColumnExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table hospital_capabilities
                        modify column capability_value varchar(32) not null
                        """);
            }
        }

        assertTargetColumn(connection);
        recordMigration(connection);
        log.info(
                "hospital_capabilities.capability_value VARCHAR(32) 마이그레이션을 완료했습니다."
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
                            "병원 역량 컬럼 마이그레이션 DB 잠금을 획득하지 못했습니다."
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
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    log.warn("병원 역량 컬럼 마이그레이션 DB 잠금이 해제되지 않았습니다.");
                }
            }
        } catch (SQLException exception) {
            log.warn("병원 역량 컬럼 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
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

    private void assertExistingValuesFit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select max(char_length(capability_value))
                       from hospital_capabilities
                     """)) {
            if (resultSet.next() && resultSet.getInt(1) > TARGET_LENGTH) {
                throw new IllegalStateException(
                        "병원 역량 값이 VARCHAR(32) 허용 길이를 초과합니다."
                );
            }
        }
    }

    private void assertTargetColumn(Connection connection) throws SQLException {
        if (!targetColumnExists(connection)) {
            throw new IllegalStateException(
                    "hospital_capabilities.capability_value가 VARCHAR(32)가 아닙니다."
            );
        }
    }

    private boolean targetColumnExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select data_type, character_maximum_length, is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'hospital_capabilities'
                   and column_name = 'capability_value'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && "varchar".equalsIgnoreCase(resultSet.getString("data_type"))
                        && resultSet.getInt("character_maximum_length") == TARGET_LENGTH
                        && "NO".equals(resultSet.getString("is_nullable"));
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
}
