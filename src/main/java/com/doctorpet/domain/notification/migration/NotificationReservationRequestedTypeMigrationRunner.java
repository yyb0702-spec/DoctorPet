package com.doctorpet.domain.notification.migration;

// 병원 수신 "새 예약 요청" 알림 유형(RESERVATION_REQUESTED, #166)을 운영 MySQL의 notifications.type ENUM에
// 추가한다. 엔티티는 EnumType.STRING이지만 실제 컬럼은 ENUM이라 값을 넣기 전에 ENUM 정의부터 넓혀야 한다.
// Flyway 없이 ddl-auto=update로 운영하므로 schema_migrations 마커 + get_lock으로 멱등·직렬화한다
// (NotificationWaitlistOfferedTypeMigrationRunner와 동일 관례).

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

/** 병원 수신 새 예약 요청 알림 유형을 기존 notifications.type MySQL ENUM에 안전하게 추가한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationReservationRequestedTypeMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "notification_reservation_requested_type_v1";
    static final String NEW_TYPE = "RESERVATION_REQUESTED";
    // 대기열 승급 유형 마이그레이션이 정정하는 과거 오타 값. 이 값이 남아 있으면 아래 목표 ENUM으로 바로
    // 바꿀 수 없다(그 값을 쓰는 기존 행이 잘려 나간다) — 그 마이그레이션이 먼저 끝나야 한다.
    private static final String LEGACY_CANCELLED_TYPE = "RESERVATION_HOSPITAL_CANCELLED";
    private static final String LOCK_NAME = "doctorpet:notification_reservation_requested_type_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    // 목표 ENUM. 기존 값 전체를 포함한 상위집합이라, 대기열 유형 마이그레이션이 이 러너보다 뒤에 실행돼도
    // 자기 값(RESERVATION_WAITLIST_OFFERED)이 이미 있는 것을 보고 컬럼을 되돌리지 않는다.
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
                log.info("새 예약 요청 알림 enum 선행 마이그레이션 생략: 신규 DB라 notifications 테이블이 없습니다.");
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
        if (!columnType.contains(NEW_TYPE)) {
            // 오타 값 정정(대기열 유형 마이그레이션)이 아직 끝나지 않은 DB다. 여기서 목표 ENUM을 그대로 적용하면
            // 그 값을 쓰는 기존 행이 잘려 나가므로, 데이터를 건드리지 않고 멈춰 순서 위반을 드러낸다.
            if (columnType.contains(LEGACY_CANCELLED_TYPE)) {
                throw new IllegalStateException(
                        "notifications.type에 " + LEGACY_CANCELLED_TYPE + "가 남아 있습니다. "
                                + "대기열 승급 알림 유형 마이그레이션이 먼저 적용돼야 합니다."
                );
            }
            alterNotificationTypeColumn(connection);
        }
        assertTargetSchema(connection);
        recordMigration(connection);
        log.info("새 예약 요청 알림 유형 마이그레이션 완료: {}", NEW_TYPE);
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

    private void alterNotificationTypeColumn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "alter table notifications modify column type " + NOTIFICATION_ENUM + " not null"
            );
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        String columnType = notificationTypeColumnType(connection);
        if (columnType == null || !columnType.contains(NEW_TYPE)) {
            throw new IllegalStateException("notifications.type enum에 " + NEW_TYPE + "가 없습니다.");
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
                    throw new IllegalStateException("새 예약 요청 알림 enum 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("새 예약 요청 알림 enum 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
