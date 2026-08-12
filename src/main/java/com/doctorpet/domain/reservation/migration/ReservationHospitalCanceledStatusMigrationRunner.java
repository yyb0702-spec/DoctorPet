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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 예약 상태 enum의 병원 취소 명칭을 프로젝트 표준(CANCELED)에 맞게 정리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationHospitalCanceledStatusMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_hospital_canceled_status_v4";
    private static final String LOCK_NAME = "doctorpet:reservation_hospital_canceled_status_v4";
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final String REASON_COLUMN = "hospital_cancel_reason";
    private static final String CANCELED_AT_COLUMN = "hospital_canceled_at";

    private static final String STATUS_ENUM_WITH_LEGACY =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','HOSPITAL_CANCELLED',"
                    + "'HOSPITAL_CANCELED','IN_TREATMENT','NO_SHOW','NO_SHOW_PENDING',"
                    + "'REJECTED','REQUESTED','TREATMENT_COMPLETED')";
    private static final String STATUS_ENUM =
            "enum('CANCELED','CHECKED_IN','CONFIRMED','HOSPITAL_CANCELED',"
                    + "'IN_TREATMENT','NO_SHOW','NO_SHOW_PENDING','REJECTED',"
                    + "'REQUESTED','TREATMENT_COMPLETED')";
    private static final String EVENT_ENUM_WITH_LEGACY =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','HOSPITAL_CANCELLED',"
                    + "'HOSPITAL_CANCELED','MANUAL_NO_SHOW','NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";
    private static final String EVENT_ENUM =
            "enum('AUTO_NO_SHOW','AUTO_NO_SHOW_PENDING','CHECKED_IN','HOSPITAL_CANCELED',"
                    + "'MANUAL_NO_SHOW','NO_SHOW_CORRECTED','TIMEOUT_REJECTED')";
    private static final String NOTIFICATION_ENUM_WITH_LEGACY =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELLED','RESERVATION_HOSPITAL_CANCELED',"
                    + "'RESERVATION_REJECTED')";
    private static final String NOTIFICATION_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED')";

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
            assertStatusColumn(connection);
            assertEventTypeColumn(connection);
            assertNotificationTypeColumn(connection);
            assertHospitalCancelColumns(connection);
            return;
        }

        String columnType = statusColumnType(connection);
        if (columnType == null) {
            throw new IllegalStateException("reservations.status 컬럼이 없습니다.");
        }
        if (columnType.contains("HOSPITAL_CANCELLED")) {
            alterStatusColumn(connection, STATUS_ENUM_WITH_LEGACY);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update reservations set status = 'HOSPITAL_CANCELED' "
                                + "where status = 'HOSPITAL_CANCELLED'"
                );
            }
            alterStatusColumn(connection, STATUS_ENUM);
        }

        String eventTypeColumnType = eventTypeColumnType(connection);
        if (eventTypeColumnType == null) {
            throw new IllegalStateException("reservation_events.event_type 컬럼이 없습니다.");
        }
        if (eventTypeColumnType.contains("HOSPITAL_CANCELLED")) {
            alterEventTypeColumn(connection, EVENT_ENUM_WITH_LEGACY);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update reservation_events set event_type = 'HOSPITAL_CANCELED' "
                                + "where event_type = 'HOSPITAL_CANCELLED'"
                );
            }
            alterEventTypeColumn(connection, EVENT_ENUM);
        }

        String notificationTypeColumnType = notificationTypeColumnType(connection);
        if (notificationTypeColumnType == null) {
            throw new IllegalStateException("notifications.type 컬럼이 없습니다.");
        }
        if (notificationTypeColumnType.contains("RESERVATION_HOSPITAL_CANCELLED")
                || !notificationTypeColumnType.contains("PAYMENT_PENDING")
                || !notificationTypeColumnType.contains("RESERVATION_HOSPITAL_CANCELED")) {
            alterNotificationTypeColumn(connection, NOTIFICATION_ENUM_WITH_LEGACY);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update notifications set type = 'RESERVATION_HOSPITAL_CANCELED' "
                                + "where type = 'RESERVATION_HOSPITAL_CANCELLED'"
                );
            }
            alterNotificationTypeColumn(connection, NOTIFICATION_ENUM);
        }

        assertStatusColumn(connection);
        assertEventTypeColumn(connection);
        assertNotificationTypeColumn(connection);
        ensureHospitalCancelColumns(connection);
        assertHospitalCancelColumns(connection);
        recordMigration(connection);
        log.info("병원 취소 예약 상태 명칭 마이그레이션 완료");
    }

    private void alterStatusColumn(Connection connection, String enumDefinition)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "alter table reservations modify column status "
                            + enumDefinition + " not null"
            );
        }
    }

    private void assertStatusColumn(Connection connection) throws SQLException {
        String columnType = statusColumnType(connection);
        if (columnType == null || !columnType.contains("HOSPITAL_CANCELED")
                || columnType.contains("HOSPITAL_CANCELLED")) {
            throw new IllegalStateException(
                    "reservations.status enum에 HOSPITAL_CANCELED가 올바르게 반영되지 않았습니다."
            );
        }
    }

    private void assertEventTypeColumn(Connection connection) throws SQLException {
        String columnType = eventTypeColumnType(connection);
        if (columnType == null || !columnType.contains("HOSPITAL_CANCELED")
                || columnType.contains("HOSPITAL_CANCELLED")) {
            throw new IllegalStateException(
                    "reservation_events.event_type enum에 HOSPITAL_CANCELED가 올바르게 반영되지 않았습니다."
            );
        }
    }

    private void assertNotificationTypeColumn(Connection connection) throws SQLException {
        String columnType = notificationTypeColumnType(connection);
        if (columnType == null || !columnType.contains("PAYMENT_PENDING")
                || !columnType.contains("RESERVATION_HOSPITAL_CANCELED")
                || columnType.contains("RESERVATION_HOSPITAL_CANCELLED")) {
            throw new IllegalStateException(
                    "notifications.type enum에 PAYMENT_PENDING과 RESERVATION_HOSPITAL_CANCELED가 올바르게 반영되지 않았습니다."
            );
        }
    }

    private void ensureHospitalCancelColumns(Connection connection) throws SQLException {
        if (!columnExists(connection, REASON_COLUMN)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "alter table reservations add column hospital_cancel_reason varchar(255) null"
                );
            }
        }
        if (!columnExists(connection, CANCELED_AT_COLUMN)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "alter table reservations add column hospital_canceled_at datetime(6) null"
                );
            }
        }
    }

    private void assertHospitalCancelColumns(Connection connection) throws SQLException {
        if (!columnExists(connection, REASON_COLUMN) || !columnExists(connection, CANCELED_AT_COLUMN)) {
            throw new IllegalStateException(
                    "reservations 병원 취소 컬럼이 올바르게 반영되지 않았습니다."
            );
        }
    }

    private boolean columnExists(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private String statusColumnType(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = 'status'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("column_type") : null;
            }
        }
    }

    private String eventTypeColumnType(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_events'
                   and column_name = 'event_type'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("column_type") : null;
            }
        }
    }

    private String notificationTypeColumnType(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = 'type'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("column_type") : null;
            }
        }
    }

    private void alterEventTypeColumn(Connection connection, String enumDefinition)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "alter table reservation_events modify column event_type "
                            + enumDefinition + " not null"
            );
        }
    }

    private void alterNotificationTypeColumn(Connection connection, String enumDefinition)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "alter table notifications modify column type "
                            + enumDefinition + " not null"
            );
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
                    throw new IllegalStateException("병원 취소 상태 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("병원 취소 상태 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
