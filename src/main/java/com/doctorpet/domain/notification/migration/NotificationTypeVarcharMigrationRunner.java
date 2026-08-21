package com.doctorpet.domain.notification.migration;

// notifications의 유형 컬럼에 대한 "DB가 값을 막지 않게" 만드는 스키마 정리(이슈 #176). 값이 늘 때마다 전용
// 확장 마이그레이션을 만들어야 했던 원인을 둘 다 없앤다.
//   ① type·resource_type이 MySQL native ENUM이던 것 → VARCHAR로 전환한다(기존 DB).
//   ② Hibernate가 VARCHAR enum 컬럼에 함께 만드는 CHECK 제약(`col in ('A','B',...)`) → 드롭한다(신규 DB).
// ②를 빼면 전환이 무의미하다 — ENUM 대신 CHECK가 같은 역할을 해서 값 추가에 여전히 DDL이 필요하다.
// 엔티티는 @JdbcTypeCode(SqlTypes.VARCHAR)로 신규 DB의 생성 타입을 못박고, 이 러너가 나머지를 정리한다.
// Flyway 없이 ddl-auto=update로 운영하므로 schema_migrations 마커 + get_lock으로 멱등·직렬화한다
// (NotificationRecipientMigrationRunner와 동일 관례). 넓히는 방향(ENUM→VARCHAR)이라 데이터 손실이 없다.

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** notifications의 유형 컬럼(type·resource_type)을 ENUM에서 VARCHAR로 전환해 값 추가에 DDL이 필요 없게 만든다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTypeVarcharMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "notification_type_varchar_v1";
    // 여유를 둔 목표 길이. 현재 최장 상수는 RESERVATION_HOSPITAL_CANCELED(29자)·RESERVATION_WAITLIST(20자)로,
    // ENUM 시절 length(30·20)는 여유가 1·0이었다. 엔티티 @Column(length)와 같은 값을 쓴다.
    private static final int TARGET_LENGTH = 40;
    static final String TARGET_COLUMN_TYPE = "varchar(" + TARGET_LENGTH + ")";
    private static final Pattern VARCHAR_LENGTH = Pattern.compile("^varchar\\((\\d+)\\)$");
    static final String TYPE_COLUMN = "type";
    static final String RESOURCE_TYPE_COLUMN = "resource_type";
    // CHECK 제약 드롭 대상. recipient_type은 이미 varchar(20)이라 타입 전환 대상은 아니지만(#141 러너가 바꿔놨다),
    // 신규 DB에서는 Hibernate가 같은 CHECK 제약을 만들어 값 추가에 DDL이 필요해지므로 함께 정리한다(이슈 #176 범위).
    private static final List<String> ENUM_COLUMNS =
            List.of(TYPE_COLUMN, RESOURCE_TYPE_COLUMN, "recipient_type");
    // 대기열 승급 유형 도입 전에 쓰였던 오타 값. ENUM 시절에는 전용 러너가 두 단계 ALTER로 정정했는데, VARCHAR로
    // 전환하면 DB가 값을 막지 않아 이 문자열이 그대로 남을 수 있다 — 남으면 목록 조회에서 enum 파싱이 깨지므로
    // 전환과 같은 잠금 안에서 함께 정정한다(정정 러너를 제거해도 데이터 보정 보장이 사라지지 않게 하기 위함).
    private static final String LEGACY_TYPE = "RESERVATION_HOSPITAL_CANCELLED";
    private static final String CORRECTED_TYPE = "RESERVATION_HOSPITAL_CANCELED";
    private static final String LOCK_NAME = "doctorpet:notification_type_varchar_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        executeMigration(false);
    }

    /** 기존 DB의 ENUM 컬럼을 Hibernate 초기화보다 먼저 VARCHAR로 바꾼다. 빈 DB는 JPA 생성 뒤에 실행한다. */
    void migrateBeforeJpa() {
        executeMigration(true);
    }

    private void executeMigration(boolean skipWhenSchemaIsAbsent) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            if (skipWhenSchemaIsAbsent && !tableExists(connection)) {
                log.info("알림 유형 컬럼 VARCHAR 선행 마이그레이션 생략: 신규 DB라 notifications 테이블이 없습니다.");
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

    // 마커가 있어도 컬럼 타입은 매 부팅 확인한다 — 누군가 수동으로 ENUM으로 되돌린 드리프트를 스스로 복구한다
    // (information_schema 조회 2건이라 정상 상태의 비용은 무시할 수 있다).
    private void migrate(Connection connection) throws SQLException {
        boolean converted = convertToVarchar(connection, TYPE_COLUMN, true);
        converted |= convertToVarchar(connection, RESOURCE_TYPE_COLUMN, false);

        if (converted) {
            // VARCHAR가 된 뒤에야 정정할 수 있다 — ENUM 상태에서는 목표 값이 정의에 없으면 UPDATE 자체가 실패한다.
            int corrected = correctLegacyTypeValues(connection);
            warnOnUnknownValues(connection);
            log.info("알림 유형 컬럼 VARCHAR 전환 완료: 오타 값 {}건 정정", corrected);
        }

        // 타입 전환과 무관하게 매 부팅 확인한다. 신규 DB는 컬럼이 이미 varchar로 생성돼(엔티티 애너테이션) 위 전환이
        // 일어나지 않는데, 바로 그 생성 시점에 Hibernate가 CHECK 제약을 함께 만든다 — 전환 여부와 독립적이다.
        int droppedChecks = dropEnumCheckConstraints(connection);
        if (droppedChecks > 0) {
            log.info("notifications의 enum CHECK 제약 {}건을 드롭했습니다.", droppedChecks);
        }

        assertTargetSchema(connection);
        recordMigration(connection);
    }

    /**
     * 유형 컬럼에 걸린 CHECK 제약을 드롭한다. 이미 없으면 아무것도 하지 않는다(멱등).
     *
     * <p>Hibernate는 `@Enumerated(EnumType.STRING)` 컬럼을 VARCHAR로 만들 때 허용 값을 열거하는 CHECK 제약을
     * 함께 생성한다(`check (type in ('NO_SHOW',...))`). `@Column(columnDefinition = ...)`으로도 억제되지 않음을
     * 실측 확인했다. 이 제약이 남으면 ENUM을 없앤 의미가 사라진다 — enum에 값을 추가했을 때 기존 DB에서 그 값의
     * INSERT만 실패하는 증상이 MySQL 1265(ENUM) 대신 3819(CHECK)로 이름만 바뀐 채 그대로 재현된다.
     *
     * <p>제약 이름은 MySQL이 `notifications_chk_N`으로 자동 부여하고 N은 생성 순서에 따라 달라지므로 하드코딩하지
     * 않고, CHECK 절이 대상 컬럼을 backtick으로 참조하는 것만 골라 드롭한다(backtick 경계 덕분에 `resource_type`
     * 제약이 `type` 매칭에 걸리지 않는다). `ddl-auto=update`는 이미 있는 테이블에 CHECK를 다시 만들지 않으므로
     * (실측 확인) 한 번 드롭하면 유지되며, 그래도 매 부팅 확인해 드리프트를 스스로 복구한다.
     *
     * @return 드롭한 제약 수
     */
    private int dropEnumCheckConstraints(Connection connection) throws SQLException {
        List<String> names = enumCheckConstraintNames(connection);
        for (String name : names) {
            try (Statement statement = connection.createStatement()) {
                // MySQL 8.0.16+ 문법. 제약 이름은 information_schema에서 읽은 값이라 외부 입력이 아니다.
                statement.executeUpdate("alter table notifications drop check `" + name + "`");
            }
        }
        return names.size();
    }

    // 유형 컬럼을 참조하는 CHECK 제약 이름 목록. 대상 컬럼 외의 CHECK 제약은 건드리지 않는다.
    private List<String> enumCheckConstraintNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select tc.constraint_name, cc.check_clause
                  from information_schema.table_constraints tc
                  join information_schema.check_constraints cc
                    on tc.constraint_schema = cc.constraint_schema
                   and tc.constraint_name = cc.constraint_name
                 where tc.table_schema = database()
                   and tc.table_name = 'notifications'
                   and tc.constraint_type = 'CHECK'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String clause = resultSet.getString("check_clause");
                    if (clause != null && referencesEnumColumn(clause)) {
                        names.add(resultSet.getString("constraint_name"));
                    }
                }
            }
        }
        return names;
    }

    private boolean referencesEnumColumn(String checkClause) {
        return ENUM_COLUMNS.stream().anyMatch(column -> checkClause.contains("`" + column + "`"));
    }

    /**
     * 컬럼을 목표 VARCHAR로 바꾼다. 이미 목표 길이 이상의 VARCHAR면 아무것도 하지 않는다(멱등).
     *
     * <p>ENUM·좁은 VARCHAR 어느 상태에서 와도 같은 ALTER 한 번으로 목표에 도달한다 — MySQL은 ENUM을 VARCHAR로
     * 바꿀 때 각 행의 값을 라벨 문자열로 옮기므로 데이터가 보존된다.
     *
     * <p>넓히기만 한다(정확히 일치를 요구하지 않는다). 나중에 엔티티 `@Column(length)`를 40보다 크게 늘리면
     * 신규 DB의 컬럼이 그 길이로 생성되는데, 정확히 `varchar(40)`을 요구하면 이 러너가 그걸 40으로 되돌려
     * 줄이게 된다 — 그러면 길이를 늘린 이유(더 긴 상수)가 조용히 무너진다.
     *
     * @return 실제로 ALTER를 실행했으면 true
     */
    private boolean convertToVarchar(Connection connection, String column, boolean notNull) throws SQLException {
        String columnType = columnType(connection, column);
        if (columnType == null) {
            throw new IllegalStateException("notifications." + column + " 컬럼이 없습니다.");
        }
        if (isVarcharAtLeastTargetLength(columnType)) {
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "alter table notifications modify column " + column + " " + TARGET_COLUMN_TYPE
                            + (notNull ? " not null" : " null")
            );
        }
        log.info("notifications.{} 컬럼을 {}에서 {}로 전환했습니다.", column, columnType, TARGET_COLUMN_TYPE);
        return true;
    }

    // 오타 값이 남은 DB(대기열 유형 정정 마이그레이션을 거치지 않은 사본)를 보정한다. 정상 DB에서는 0건이다.
    private int correctLegacyTypeValues(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update notifications set type = ? where type = ?")) {
            statement.setString(1, CORRECTED_TYPE);
            statement.setString(2, LEGACY_TYPE);
            return statement.executeUpdate();
        }
    }

    // DB가 더 이상 값을 막아주지 않으므로, 애플리케이션 enum이 모르는 값이 남아 있으면 경고로 드러낸다.
    // 예외로 부팅을 막지는 않는다 — 이전 버전으로 롤백한 직후(신버전이 쓴 값이 남은 상태)처럼 정상적으로
    // 모르는 값이 있을 수 있고, VARCHAR 전환의 이점이 바로 그 전방 호환이다. 해당 행의 조회만 깨진다.
    private void warnOnUnknownValues(Connection connection) throws SQLException {
        warnOnUnknownValues(connection, TYPE_COLUMN,
                Arrays.stream(NotificationType.values()).map(Enum::name).toList());
        warnOnUnknownValues(connection, RESOURCE_TYPE_COLUMN,
                Arrays.stream(NotificationResourceType.values()).map(Enum::name).toList());
    }

    private void warnOnUnknownValues(Connection connection, String column, List<String> known) throws SQLException {
        List<String> unknown = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select distinct " + column + " from notifications where " + column + " is not null")) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                if (!known.contains(value)) {
                    unknown.add(value);
                }
            }
        }
        if (!unknown.isEmpty()) {
            log.warn(
                    "notifications.{}에 애플리케이션 enum이 모르는 값이 있습니다: {} — 해당 알림의 조회가 실패할 수 있습니다.",
                    column, unknown
            );
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        assertColumnIsTargetVarchar(connection, TYPE_COLUMN);
        assertColumnIsTargetVarchar(connection, RESOURCE_TYPE_COLUMN);
        // CHECK 제약이 남아 있으면 ENUM을 없앤 의미가 사라지므로 조용히 통과시키지 않는다.
        List<String> remaining = enumCheckConstraintNames(connection);
        if (!remaining.isEmpty()) {
            throw new IllegalStateException(
                    "notifications 유형 컬럼에 CHECK 제약이 남아 있습니다: " + remaining);
        }
    }

    private void assertColumnIsTargetVarchar(Connection connection, String column) throws SQLException {
        String columnType = columnType(connection, column);
        if (!isVarcharAtLeastTargetLength(columnType)) {
            throw new IllegalStateException(
                    "notifications." + column + "이 " + TARGET_COLUMN_TYPE + " 이상의 varchar가 아닙니다: " + columnType);
        }
    }

    private boolean isVarcharAtLeastTargetLength(String columnType) {
        if (columnType == null) {
            return false;
        }
        Matcher matcher = VARCHAR_LENGTH.matcher(columnType);
        return matcher.matches() && Integer.parseInt(matcher.group(1)) >= TARGET_LENGTH;
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

    private String columnType(Connection connection, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = ?
                """)) {
            statement.setString(1, column);
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
                    throw new IllegalStateException("알림 유형 컬럼 VARCHAR 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("알림 유형 컬럼 VARCHAR 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
