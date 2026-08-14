package com.doctorpet.domain.chat.migration;

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

/**
 * 기존 chat_messages에 client_message_id를 안전하게 추가한다.
 *
 * <p>Hibernate가 NOT NULL·UNIQUE를 먼저 적용하면 기존 행 때문에 부팅이 실패할 수 있으므로,
 * EntityManagerFactory보다 먼저 nullable 추가 → UUID 백필 → UNIQUE → NOT NULL 순으로 적용한다.
 * 배포 중 구버전 인스턴스가 컬럼 없이 INSERT하는 경우도 BEFORE INSERT 트리거가 UUID를 채운다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "chat.client-message-id-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatMessageClientMessageIdMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "chat_message_client_message_id_v1";
    static final String UNIQUE_INDEX_NAME = "uk_chat_messages_reservation_member_client";
    static final String BACKFILL_TRIGGER = "trg_chat_messages_client_message_id";
    private static final String LOCK_NAME = "doctorpet:chat_message_client_message_id_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        executeMigration(false);
    }

    /** 신규 DB에는 아직 chat_messages가 없으므로 건너뛰고, JPA 초기화 뒤 run()에서 마커를 남긴다. */
    void migrateBeforeJpa() {
        executeMigration(true);
    }

    private void executeMigration(boolean skipWhenSchemaIsAbsent) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            if (skipWhenSchemaIsAbsent && !tableExists(connection, "chat_messages")) {
                log.info("채팅 멱등키 선행 마이그레이션 생략: 신규 DB라 chat_messages 테이블이 없습니다.");
                return null;
            }
            ensureSchemaMigrationsTable(connection);
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

        ensureNullableClientMessageIdColumn(connection);
        // 트리거를 먼저 설치하면 구버전 INSERT가 백필·NOT NULL 적용 사이에 끼어들어도 NULL이 남지 않는다.
        applyLegacyInsertTrigger(connection);
        int backfilled = backfillMissingClientMessageIds(connection);
        verifyNoMissingClientMessageIds(connection);
        applyUniqueIndex(connection);
        applyNotNullConstraint(connection);
        assertTargetSchema(connection);
        recordMigration(connection);

        log.info("채팅 멱등키 마이그레이션 완료: 기존 메시지 {}건 UUID 백필 및 {} 확인",
                backfilled, UNIQUE_INDEX_NAME);
    }

    private void ensureNullableClientMessageIdColumn(Connection connection) throws SQLException {
        if (!columnExists(connection, "client_message_id")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("alter table chat_messages "
                        + "add column client_message_id varchar(36) null");
            }
        }
    }

    private void applyLegacyInsertTrigger(Connection connection) throws SQLException {
        // 부분 적용 후 재시작하는 경우에도 기존 트리거를 잠시 DROP하면 그 짧은 창에 구버전
        // INSERT가 NOT NULL 위반으로 실패할 수 있다. 최초 설치만 하고 이후에는 유지한다.
        if (triggerExists(connection)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("create trigger " + BACKFILL_TRIGGER
                    + " before insert on chat_messages for each row "
                    + "set new.client_message_id = if(new.client_message_id is null "
                    + "or char_length(trim(new.client_message_id)) = 0, uuid(), new.client_message_id)");
        }
    }

    private int backfillMissingClientMessageIds(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("update chat_messages set client_message_id = uuid() "
                    + "where client_message_id is null or char_length(trim(client_message_id)) = 0");
        }
    }

    private void verifyNoMissingClientMessageIds(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from chat_messages "
                     + "where client_message_id is null or char_length(trim(client_message_id)) = 0")) {
            if (!resultSet.next() || resultSet.getInt(1) > 0) {
                throw new IllegalStateException("client_message_id 백필 누락 메시지가 있습니다.");
            }
        }
    }

    private void applyUniqueIndex(Connection connection) throws SQLException {
        if (uniqueIndexExists(connection)) {
            return;
        }
        if (indexNameExists(connection, UNIQUE_INDEX_NAME)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("drop index " + UNIQUE_INDEX_NAME + " on chat_messages");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("create unique index " + UNIQUE_INDEX_NAME
                    + " on chat_messages (reservation_id, member_id, client_message_id)");
        }
    }

    private void applyNotNullConstraint(Connection connection) throws SQLException {
        if (columnNullable(connection, "client_message_id")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("alter table chat_messages "
                        + "modify column client_message_id varchar(36) not null");
            }
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (columnNullable(connection, "client_message_id")) {
            throw new IllegalStateException("chat_messages.client_message_id NOT NULL 제약이 없습니다.");
        }
        if (!uniqueIndexExists(connection)) {
            throw new IllegalStateException("chat_messages 멱등 UNIQUE 제약이 없습니다.");
        }
        if (!triggerExists(connection)) {
            throw new IllegalStateException("chat_messages 구버전 INSERT 호환 트리거가 없습니다.");
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema = database() and table_name = ?
                """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean columnExists(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.columns
                 where table_schema = database() and table_name = 'chat_messages' and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean columnNullable(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select is_nullable from information_schema.columns
                 where table_schema = database() and table_name = 'chat_messages' and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("chat_messages." + columnName + " 컬럼이 없습니다.");
                }
                return "YES".equalsIgnoreCase(resultSet.getString(1));
            }
        }
    }

    private boolean uniqueIndexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from (
                    select index_name from information_schema.statistics
                     where table_schema = database() and table_name = 'chat_messages'
                       and index_name = ? and non_unique = 0
                     group by index_name
                    having group_concat(column_name order by seq_in_index)
                           = 'reservation_id,member_id,client_message_id'
                ) matching_indexes
                """)) {
            statement.setString(1, UNIQUE_INDEX_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean indexNameExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.statistics
                 where table_schema = database() and table_name = 'chat_messages' and index_name = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean triggerExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.triggers
                 where trigger_schema = database() and trigger_name = ?
                """)) {
            statement.setString(1, BACKFILL_TRIGGER);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
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
                values (?, now()) on duplicate key update migration_key = migration_key
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
                    throw new IllegalStateException("채팅 멱등키 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseMigrationLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("채팅 멱등키 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
