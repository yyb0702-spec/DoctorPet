package com.doctorpet.domain.notification.migration;

// notification_preferences의 enum 컬럼에 붙는 CHECK 제약을 드롭한다(고도화 3.9, 이슈 #176과 같은 원인).

import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 수신 설정 테이블의 `notification_type`·`channel`에 Hibernate가 만드는 값 열거 CHECK 제약을 드롭한다.
 *
 * <p>이 테이블은 <b>신규 테이블이라 모든 DB에서 새로 생성</b>되므로, `notifications`와 달리 오래된 DB에도 예외 없이
 * CHECK 제약이 붙는다. 남겨두면 {@link NotificationType}에 값을 하나 추가할 때마다 이 테이블에도 DDL이 필요해져
 * 이슈 #176이 없앤 부채가 새 테이블로 되살아난다({@link NotificationEnumCheckConstraints} 주석 참고).
 *
 * <p>선행(pre-JPA) 실행이 아니라 <b>JPA 초기화 이후</b>에 도는 {@link ApplicationRunner}인 것이 중요하다 —
 * 제약을 만드는 주체가 Hibernate의 테이블 생성이므로, 그보다 먼저 돌면 드롭할 대상이 아직 없다
 * ({@code NotificationRecipientMigrationRunner}와 같은 배선).
 *
 * <p>테이블이 아직 없으면(다른 이유로 JPA 생성이 비활성인 환경) 조용히 건너뛴다. 매 부팅 확인하므로
 * 누군가 제약을 다시 만든 드리프트도 스스로 복구한다 — 정상 상태의 비용은 information_schema 조회 1건이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPreferenceCheckConstraintMigrationRunner implements ApplicationRunner {

    static final String TABLE = "notification_preferences";
    static final List<String> ENUM_COLUMNS = List.of("notification_type", "channel");
    static final String MIGRATION_KEY = "notification_preference_check_drop_v1";
    private static final String LOCK_NAME = "doctorpet:notification_preference_check_drop_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    // 패키지 접근 — 테스트가 제약을 직접 만들어 놓고 이 흐름을 그대로 구동한다.
    void migrate() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            if (!tableExists(connection)) {
                log.info("{} CHECK 제약 정리 생략: 테이블이 없습니다.", TABLE);
                return null;
            }
            ensureSchemaMigrationsTable(connection);
            acquireLock(connection);
            try {
                int dropped = NotificationEnumCheckConstraints.drop(connection, TABLE, ENUM_COLUMNS);
                if (dropped > 0) {
                    log.info("{}의 enum CHECK 제약 {}건을 드롭했습니다.", TABLE, dropped);
                }
                assertNoCheckConstraint(connection);
                recordMigration(connection);
            } finally {
                releaseLock(connection);
            }
            return null;
        });
    }

    // CHECK 제약이 남아 있으면 유형·채널 추가에 DDL이 다시 필요해지므로 조용히 통과시키지 않는다.
    private void assertNoCheckConstraint(Connection connection) throws SQLException {
        List<String> remaining = NotificationEnumCheckConstraints.names(connection, TABLE, ENUM_COLUMNS);
        if (!remaining.isEmpty()) {
            throw new IllegalStateException(
                    TABLE + "의 enum 컬럼에 CHECK 제약이 남아 있습니다: " + remaining);
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from information_schema.tables
                 where table_schema = database() and table_name = ?
                """)) {
            statement.setString(1, TABLE);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
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
                    throw new IllegalStateException("수신 설정 CHECK 제약 정리 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("수신 설정 CHECK 제약 정리 DB 잠금 해제에 실패했습니다.", exception);
        }
    }
}
