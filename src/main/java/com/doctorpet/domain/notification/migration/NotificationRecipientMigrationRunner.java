package com.doctorpet.domain.notification.migration;

// 알림 수신자 모델 확장(고도화 3.10)의 운영 스키마 마이그레이션. 기존 notifications 행을 (MEMBER, member_id)로
// 백필하고, recipient_type·recipient_id에 NOT NULL을 적용하며, member_id를 nullable로 완화하고, 조회 인덱스를
// recipient 기준으로 교체한다. Flyway 없이 ddl-auto=update로 운영하므로 "한 번만 실행돼야 하는" 백필·제약 적용을
// schema_migrations 마커 + get_lock으로 멱등·직렬화한다(ReservationApprovalDeadlineMigrationRunner와 동일 관례).

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
        prefix = "notification.recipient-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class NotificationRecipientMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "notification_recipient_model_v1";
    static final String CREATED_INDEX = "idx_notifications_recipient_created";
    static final String READ_INDEX = "idx_notifications_recipient_read";
    // blue/green 배포 호환용 BEFORE INSERT 트리거 이름(리뷰 지적 P1). recipient_*가 비면 (MEMBER, member_id)로 채운다.
    static final String BACKFILL_TRIGGER = "trg_notifications_recipient_backfill";
    private static final String LEGACY_CREATED_INDEX = "idx_notifications_member_created";
    private static final String LEGACY_READ_INDEX = "idx_notifications_member_read";
    private static final String LOCK_NAME = "doctorpet:notification_recipient_model_v1";
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

        int backfilled;
        try (Statement statement = connection.createStatement()) {
            // 기존 행은 모두 회원(보호자) 수신이었다 — recipient를 (MEMBER, member_id)로 채운다.
            backfilled = statement.executeUpdate("""
                    update notifications
                       set recipient_type = 'MEMBER',
                           recipient_id = member_id
                     where recipient_type is null
                        or recipient_id is null
                    """);
        }

        int missing = countMissingRecipients(connection);
        if (missing > 0) {
            throw new IllegalStateException(
                    "recipient 백필 누락 알림이 " + missing + "건 있습니다."
            );
        }

        // NOT NULL을 걸기 전에 호환 트리거를 먼저 만든다 — blue/green 배포 중 아직 활성인 구버전(recipient_*를
        // 모르는 코드)이 member_id만으로 INSERT해도 트리거가 (MEMBER, member_id)로 채워 NOT NULL을 만족시키고
        // recipient 조회에도 보이게 한다(리뷰 지적 P1). 순서가 중요하다: 트리거 → NOT NULL.
        applyRecipientBackfillTrigger(connection);
        applyNotNull(connection, "recipient_type", "varchar(20)");
        applyNotNull(connection, "recipient_id", "bigint");
        relaxMemberIdNullable(connection);
        applyIndex(connection, CREATED_INDEX, "recipient_type,recipient_id,created_at");
        applyIndex(connection, READ_INDEX, "recipient_type,recipient_id,read_at");
        dropLegacyIndex(connection, LEGACY_CREATED_INDEX);
        dropLegacyIndex(connection, LEGACY_READ_INDEX);
        assertTargetSchema(connection);
        recordMigration(connection);

        log.info(
                "알림 수신자 모델 마이그레이션 완료: {}건 백필, recipient_type·recipient_id NOT NULL, member_id nullable, {}·{} 인덱스 확인",
                backfilled, CREATED_INDEX, READ_INDEX
        );
    }

    // blue/green 호환 BEFORE INSERT 트리거를 (재)생성한다(리뷰 지적 P1). recipient_type/recipient_id가 비어 있으면
    // (MEMBER, member_id)로 채운다 — 구버전 코드의 member_id-only INSERT를 NOT NULL 위반 없이 흡수하고 recipient
    // 조회에도 보이게 한다. 신규 코드는 recipient_*를 직접 채우므로 COALESCE가 기존 값을 유지해 no-op이며, HOSPITAL
    // 수신(member_id=NULL)도 recipient_*가 이미 채워져 영향받지 않는다. 구버전이 사라진 뒤엔 상시 no-op이라 무해하다.
    // SET 한 문장짜리 트리거라 BEGIN/END·DELIMITER가 필요 없어 JDBC로 그대로 실행된다. 재실행 대비 DROP IF EXISTS 선행.
    private void applyRecipientBackfillTrigger(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop trigger if exists " + BACKFILL_TRIGGER);
            statement.execute(
                    "create trigger " + BACKFILL_TRIGGER + " before insert on notifications for each row "
                            + "set new.recipient_type = coalesce(new.recipient_type, 'MEMBER'), "
                            + "new.recipient_id = coalesce(new.recipient_id, new.member_id)");
        }
    }

    private void applyNotNull(Connection connection, String column, String type) throws SQLException {
        if (columnNullable(connection, column)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "alter table notifications modify column " + column + " " + type + " not null");
            }
        }
    }

    private void relaxMemberIdNullable(Connection connection) throws SQLException {
        if (!columnNullable(connection, "member_id")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("alter table notifications modify column member_id bigint null");
            }
        }
    }

    private void applyIndex(Connection connection, String indexName, String expectedColumns)
            throws SQLException {
        if (!indexExists(connection, indexName, expectedColumns)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create index " + indexName
                        + " on notifications (" + expectedColumns + ")");
            }
        }
    }

    private void dropLegacyIndex(Connection connection, String indexName) throws SQLException {
        if (indexNameExists(connection, indexName)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("drop index " + indexName + " on notifications");
            }
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (columnNullable(connection, "recipient_type")) {
            throw new IllegalStateException("notifications.recipient_type NOT NULL 제약이 없습니다.");
        }
        if (columnNullable(connection, "recipient_id")) {
            throw new IllegalStateException("notifications.recipient_id NOT NULL 제약이 없습니다.");
        }
        if (!columnNullable(connection, "member_id")) {
            throw new IllegalStateException("notifications.member_id nullable 완화가 적용되지 않았습니다.");
        }
        if (!indexExists(connection, CREATED_INDEX, "recipient_type,recipient_id,created_at")) {
            throw new IllegalStateException("notifications의 recipient 최신순 조회 인덱스가 없습니다.");
        }
        if (!indexExists(connection, READ_INDEX, "recipient_type,recipient_id,read_at")) {
            throw new IllegalStateException("notifications의 recipient 읽음 조회 인덱스가 없습니다.");
        }
    }

    private int countMissingRecipients(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                       from notifications
                      where recipient_type is null
                         or recipient_id is null
                     """)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private boolean columnNullable(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "notifications." + columnName + " 컬럼이 없습니다."
                    );
                }
                return "YES".equalsIgnoreCase(resultSet.getString(1));
            }
        }
    }

    private boolean indexExists(Connection connection, String indexName, String expectedColumns)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = 'notifications'
                           and index_name = ?
                         group by index_name
                        having group_concat(column_name order by seq_in_index) = ?
                       ) matching_index
                """)) {
            statement.setString(1, indexName);
            statement.setString(2, expectedColumns);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean indexNameExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'notifications'
                   and index_name = ?
                """)) {
            statement.setString(1, indexName);
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
                            "알림 수신자 모델 마이그레이션 DB 잠금을 획득하지 못했습니다."
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
            log.warn("알림 수신자 모델 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
