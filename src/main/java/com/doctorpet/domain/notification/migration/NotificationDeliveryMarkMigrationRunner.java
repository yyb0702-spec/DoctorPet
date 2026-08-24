package com.doctorpet.domain.notification.migration;

// 전체 삭제가 표시용 notifications 행만 지우고 PAYMENT_PENDING의 "결제당 1회" 발행 이력은 남기게 하는
// expand 마이그레이션(PR #213 리뷰). 구버전은 notifications.dedup_key만 쓰므로, 새 mark 테이블을 만든 뒤
// INSERT 트리거를 먼저 설치하고 기존 키를 백필한다. 트리거 설치 전의 좁은 창에 구버전이 넣은 행도 백필이 뒤에서
// 흡수하고, 설치 후에는 blue/green 구버전 INSERT도 mark를 함께 남긴다.

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
        prefix = "notification.delivery-mark-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class NotificationDeliveryMarkMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "notification_delivery_marks_v1";
    static final String TABLE = "notification_delivery_marks";
    static final String UNIQUE_INDEX = "uk_notification_delivery_marks_dedup_key";
    static final String LEGACY_INSERT_TRIGGER = "trg_notifications_delivery_mark";
    private static final String LOCK_NAME = "doctorpet:notification_delivery_marks_v1";
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
        ensureSchemaMigrationsTable(connection);
        ensureMarkTable(connection);

        if (migrationApplied(connection)) {
            assertTargetSchema(connection);
            return;
        }

        // 구버전 INSERT를 먼저 받기 시작한 뒤 백필한다. 순서를 반대로 하면 백필 직후 들어온 구버전 알림이
        // 삭제와 함께 유일한 발행 이력을 잃는 blue/green 창이 생긴다.
        ensureLegacyInsertTrigger(connection);
        int backfilled = backfillLegacyDedupKeys(connection);
        assertTargetSchema(connection);
        recordMigration(connection);
        log.info("알림 발행 마커 마이그레이션 완료: 기존 dedup_key {}건 백필", backfilled);
    }

    private void ensureMarkTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists notification_delivery_marks (
                        id bigint not null auto_increment,
                        dedup_key varchar(200) not null,
                        created_at datetime(6) not null,
                        primary key (id),
                        constraint uk_notification_delivery_marks_dedup_key unique (dedup_key)
                    )
                    """);
        }
    }

    private void ensureLegacyInsertTrigger(Connection connection) throws SQLException {
        if (triggerExists(connection, LEGACY_INSERT_TRIGGER)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create trigger trg_notifications_delivery_mark
                    after insert on notifications
                    for each row
                    insert ignore into notification_delivery_marks (dedup_key, created_at)
                    select new.dedup_key, now(6)
                     where new.dedup_key is not null
                    """);
        }
    }

    private int backfillLegacyDedupKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("""
                    insert ignore into notification_delivery_marks (dedup_key, created_at)
                    select dedup_key, created_at
                      from notifications
                     where dedup_key is not null
                    """);
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (!tableExists(connection, TABLE)) {
            throw new IllegalStateException(TABLE + " 테이블이 없습니다.");
        }
        if (!uniqueIndexExists(connection, UNIQUE_INDEX)) {
            throw new IllegalStateException(TABLE + ".dedup_key UNIQUE 제약이 없습니다.");
        }
        if (!notNullColumnExists(connection, "dedup_key") || !notNullColumnExists(connection, "created_at")) {
            throw new IllegalStateException(TABLE + "의 dedup_key·created_at NOT NULL 제약이 없습니다.");
        }
        if (!triggerExists(connection, LEGACY_INSERT_TRIGGER)) {
            throw new IllegalStateException("구버전 dedup_key 동기화 트리거가 없습니다.");
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
                values (?, now(6))
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, MIGRATION_KEY);
            statement.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema = database() and table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean uniqueIndexExists(Connection connection, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select non_unique from information_schema.statistics
                 where table_schema = database() and table_name = ? and index_name = ?
                """)) {
            statement.setString(1, TABLE);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 0;
            }
        }
    }

    private boolean notNullColumnExists(Connection connection, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.columns
                 where table_schema = database()
                   and table_name = ?
                   and column_name = ?
                   and is_nullable = 'NO'
                """)) {
            statement.setString(1, TABLE);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean triggerExists(Connection connection, String trigger) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.triggers
                 where trigger_schema = database() and trigger_name = ?
                """)) {
            statement.setString(1, trigger);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException("알림 발행 마커 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("알림 발행 마커 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
