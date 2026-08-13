package com.doctorpet.domain.payment.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 기본 결제수단의 회원당 1건 제약을 MySQL에서 보장한다. MySQL에는 부분 UNIQUE 인덱스가 없으므로,
 * ACTIVE·기본값일 때만 member_id를 내보내는 생성 컬럼과 UNIQUE를 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentMethodDefaultMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "payment_method_default_v1";
    static final String ACTIVE_METHOD_INDEX_MIGRATION_KEY = "payment_method_active_member_status_index_v1";
    static final String GENERATED_COLUMN = "active_default_member_id";
    static final String UNIQUE_INDEX = "uk_payment_methods_active_default_member_id";
    static final String ACTIVE_METHOD_INDEX = "idx_payment_methods_member_id_status";
    private static final String LOCK_NAME = "doctorpet:payment_method_default_v1";
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
        migrateDefaultConstraint(connection);
        migrateActiveMethodIndex(connection);
    }

    private void migrateDefaultConstraint(Connection connection) throws SQLException {
        if (migrationApplied(connection, MIGRATION_KEY)) {
            assertDefaultConstraintSchema(connection);
            return;
        }

        if (!columnExists(connection, "is_default")) {
            execute(connection, """
                    alter table payment_methods
                    add column is_default boolean not null default false
                    """);
        }
        execute(connection, """
                update payment_methods
                   set is_default = false
                 where status <> 'ACTIVE'
                """);

        if (!columnExists(connection, GENERATED_COLUMN)) {
            execute(connection, """
                    alter table payment_methods
                    add column active_default_member_id bigint
                    generated always as (
                        case
                            when status = 'ACTIVE' and is_default = true then member_id
                            else null
                        end
                    ) stored
                    """);
        }
        if (!indexExists(connection)) {
            if (indexNameExists(connection, UNIQUE_INDEX)) {
                throw new IllegalStateException("결제수단 기본값 UNIQUE 인덱스 구성이 올바르지 않습니다.");
            }
            execute(connection, """
                    create unique index uk_payment_methods_active_default_member_id
                    on payment_methods (active_default_member_id)
                    """);
        }

        assertDefaultConstraintSchema(connection);
        recordMigration(connection, MIGRATION_KEY);
        log.info("결제수단 기본값 제약 적용 완료: {}", UNIQUE_INDEX);
    }

    private void migrateActiveMethodIndex(Connection connection) throws SQLException {
        if (migrationApplied(connection, ACTIVE_METHOD_INDEX_MIGRATION_KEY)) {
            assertActiveMethodIndex(connection);
            return;
        }

        if (!activeMethodIndexExists(connection)) {
            if (indexNameExists(connection, ACTIVE_METHOD_INDEX)) {
                throw new IllegalStateException("결제수단 활성 목록 잠금 인덱스 구성이 올바르지 않습니다.");
            }
            execute(connection, """
                    create index idx_payment_methods_member_id_status
                    on payment_methods (member_id, status)
                    """);
        }

        assertActiveMethodIndex(connection);
        recordMigration(connection, ACTIVE_METHOD_INDEX_MIGRATION_KEY);
        log.info("결제수단 활성 목록 잠금 인덱스 적용 완료: {}", ACTIVE_METHOD_INDEX);
    }

    private void assertDefaultConstraintSchema(Connection connection) throws SQLException {
        if (!columnExists(connection, "is_default") || !generatedColumnExists(connection)) {
            throw new IllegalStateException("결제수단 기본값 생성 컬럼이 없습니다.");
        }
        if (!indexExists(connection)) {
            throw new IllegalStateException("결제수단 기본값 UNIQUE 인덱스가 없습니다.");
        }
    }

    private void assertActiveMethodIndex(Connection connection) throws SQLException {
        if (!activeMethodIndexExists(connection)) {
            throw new IllegalStateException("결제수단 활성 목록 잠금 인덱스가 없습니다.");
        }
    }

    private boolean generatedColumnExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and column_name = ?
                   and generation_expression is not null
                   and generation_expression <> ''
                """)) {
            statement.setString(1, GENERATED_COLUMN);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean columnExists(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean indexNameExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and index_name = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean activeMethodIndexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_name
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and index_name = ?
                   and non_unique = 1
                 order by seq_in_index
                """)) {
            statement.setString(1, ACTIVE_METHOD_INDEX);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && "member_id".equals(resultSet.getString(1))
                        && resultSet.next()
                        && "status".equals(resultSet.getString(1))
                        && !resultSet.next();
            }
        }
    }

    private boolean indexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and index_name = ?
                   and column_name = ?
                   and non_unique = 0
                """)) {
            statement.setString(1, UNIQUE_INDEX);
            statement.setString(2, GENERATED_COLUMN);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean migrationApplied(Connection connection, String migrationKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from schema_migrations where migration_key = ?
                """)) {
            statement.setString(1, migrationKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void recordMigration(Connection connection, String migrationKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, migrationKey);
            statement.executeUpdate();
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException("결제수단 기본값 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("결제수단 기본값 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
