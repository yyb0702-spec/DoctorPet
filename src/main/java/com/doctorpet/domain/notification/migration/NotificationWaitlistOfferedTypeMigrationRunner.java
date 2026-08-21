package com.doctorpet.domain.notification.migration;

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

/** 대기열 승급 알림 유형을 기존 notifications.type MySQL ENUM에 안전하게 추가한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWaitlistOfferedTypeMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "notification_waitlist_offered_type_v1";
    private static final String LOCK_NAME = "doctorpet:notification_waitlist_offered_type_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final String NOTIFICATION_ENUM_WITH_LEGACY =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELLED','RESERVATION_HOSPITAL_CANCELED',"
                    + "'RESERVATION_REJECTED','RESERVATION_REQUESTED',"
                    + "'RESERVATION_WAITLIST_OFFERED')";
    private static final String NOTIFICATION_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED',"
                    + "'RESERVATION_REQUESTED','RESERVATION_WAITLIST_OFFERED')";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        executeMigration(false);
    }

    /** 기존 DB의 ENUM을 Hibernate 초기화보다 먼저 확장한다. 빈 DB는 JPA 생성 뒤에 실행한다. */
    void migrateBeforeJpa() {
        executeMigration(true);
    }

    private void executeMigration(boolean skipWhenSchemaIsAbsent) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            if (skipWhenSchemaIsAbsent && !tableExists(connection)) {
                log.info("대기열 승급 알림 enum 선행 마이그레이션 생략: 신규 DB라 notifications 테이블이 없습니다.");
                return null;
            }
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
        String columnType = notificationTypeColumnType(connection);
        if (columnType == null) {
            throw new IllegalStateException("notifications.type 컬럼이 없습니다.");
        }
        // 후속 VARCHAR 전환(#176) 뒤 이 호환 버전으로 롤백해도 ENUM으로 되돌리지 않는다.
        // VARCHAR는 애플리케이션이 알지 못하는 새 값을 보존할 수 있으므로, 여기서 목표 ENUM을
        // 다시 적용하면 부팅 실패 또는 값 손실이 생긴다.
        if (isVarchar(columnType)) {
            recordMigration(connection);
            log.info("대기열 승급 알림 유형 마이그레이션 생략: notifications.type이 VARCHAR입니다.");
            return;
        }
        if (columnType.contains("RESERVATION_HOSPITAL_CANCELLED")) {
            alterNotificationTypeColumn(connection, NOTIFICATION_ENUM_WITH_LEGACY);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update notifications set type = 'RESERVATION_HOSPITAL_CANCELED' "
                                + "where type = 'RESERVATION_HOSPITAL_CANCELLED'"
                );
            }
            alterNotificationTypeColumn(connection, NOTIFICATION_ENUM);
        } else if (!columnType.contains("RESERVATION_WAITLIST_OFFERED")) {
            alterNotificationTypeColumn(connection, NOTIFICATION_ENUM);
        }
        assertTargetSchema(connection);
        recordMigration(connection);
        log.info("대기열 승급 알림 유형 마이그레이션 완료");
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

    private void alterNotificationTypeColumn(Connection connection, String enumDefinition) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "alter table notifications modify column type " + enumDefinition + " not null"
            );
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        String columnType = notificationTypeColumnType(connection);
        if (columnType == null || !columnType.contains("RESERVATION_WAITLIST_OFFERED")) {
            throw new IllegalStateException("notifications.type enum에 RESERVATION_WAITLIST_OFFERED가 없습니다.");
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema = database() and table_name = 'notifications'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private String notificationTypeColumnType(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = 'type'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("column_type") : null;
            }
        }
    }

    private boolean isVarchar(String columnType) {
        return columnType.toLowerCase(java.util.Locale.ROOT).startsWith("varchar(");
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
                    throw new IllegalStateException("대기열 승급 알림 enum 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("대기열 승급 알림 enum 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
