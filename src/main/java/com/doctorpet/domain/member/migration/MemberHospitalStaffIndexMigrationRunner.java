package com.doctorpet.domain.member.migration;

// 병원 스태프 조회용 members(hospital_id, role) 인덱스를 기존 운영 DB에 한 번만 적용한다(#166).
// 엔티티 @Index는 ddl-auto=update가 새로 만드는 테이블에만 반영되므로, 이미 존재하는 members에는
// 이 러너가 같은 정의로 인덱스를 만든다. Flyway 없이 schema_migrations 마커 + get_lock으로 멱등·직렬화한다
// (ReservationNoShowIndexMigrationRunner와 동일 관례).

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

/** 병원 수신 알림 fan-out 대상 조회용 members 복합 인덱스를 운영 DB에 적용한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "member.hospital-staff-index-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MemberHospitalStaffIndexMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "member_hospital_staff_index_v1";
    static final String INDEX_NAME = "idx_members_hospital_role";
    // information_schema.statistics에서 확인할 컬럼 순서. 엔티티 @Index의 columnList와 같아야 한다 —
    // 순서가 뒤바뀐 인덱스는 hospital_id 단독 조건을 못 살리므로 같은 인덱스로 취급하지 않는다.
    private static final String EXPECTED_COLUMNS = "hospital_id,role";
    private static final String LOCK_NAME = "doctorpet:member_hospital_staff_index_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
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

        if (!indexExists(connection)) {
            // 이름은 있지만 컬럼 구성이 다른 인덱스가 남아 있으면(부분 적용 재실행, 과거 수동 생성 등) 같은
            // 이름으로 create index를 실행할 수 없다 — MySQL이 Duplicate key name으로 부팅을 중단시킨다.
            // 잘못된 정의를 지우고 올바른 정의로 다시 만들어 드리프트를 스스로 교체한다.
            if (indexNameExists(connection)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("drop index " + INDEX_NAME + " on members");
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "create index " + INDEX_NAME + " on members (" + EXPECTED_COLUMNS + ")"
                );
            }
        }

        assertTargetSchema(connection);
        recordMigration(connection);
        log.info("병원 스태프 조회 인덱스 적용 완료: {}", INDEX_NAME);
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (indexExists(connection)) {
            return;
        }
        if (indexNameExists(connection)) {
            throw new IllegalStateException("members의 병원 스태프 조회 인덱스 구성이 올바르지 않습니다.");
        }
        throw new IllegalStateException("members의 병원 스태프 조회 인덱스가 없습니다.");
    }

    private boolean indexNameExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'members'
                   and index_name = ?
                """)) {
            statement.setString(1, INDEX_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean indexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = 'members'
                           and index_name = ?
                         group by index_name
                        having group_concat(column_name order by seq_in_index) = ?
                       ) matching_index
                """)) {
            statement.setString(1, INDEX_NAME);
            statement.setString(2, EXPECTED_COLUMNS);
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
                values (?, now(6))
                on duplicate key update migration_key = migration_key
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
                    throw new IllegalStateException("병원 스태프 조회 인덱스 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseMigrationLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("병원 스태프 조회 인덱스 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
