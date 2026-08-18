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
 * 예약당 <b>활성 결제 1건</b> 제약을 MySQL에서 보장한다(고도화 3.3·3.5-a, SA §4 payments·§9-4). 셀프 복구·정정
 * 재청구는 대체된 과거 결제를 이력으로 남기므로 같은 reservation_id가 여러 행에 존재한다 — 단순
 * UNIQUE(reservation_id)로는 "활성 1건"을 표현할 수 없다. MySQL에 부분 UNIQUE 인덱스가 없으므로, 활성일 때만
 * (superseded_at IS NULL) reservation_id를 내보내는 stored 생성 컬럼 active_reservation_id와 그 UNIQUE로
 * 못박는다(payment_methods.active_default_member_id와 동일 패턴).
 *
 * <p><b>제약 교체 순서(expand→contract, SA §4)</b>: 새 UNIQUE(uk_payments_active_reservation_id)를 먼저 만들고
 * 검증한 <b>뒤에</b> 구 UNIQUE(uk_payments_reservation_id)를 제거한다. 순서를 뒤집으면 두 제약이 모두 없는 창에서
 * 한 예약에 활성 결제가 2건 생길 수 있다. 이 러너는 앱이 요청을 받기 전(ApplicationRunner)에 완료되므로, 재청구
 * 경로가 서빙되는 시점에는 새 UNIQUE가 이미 존재한다.
 *
 * <p>base 컬럼(superseded_at·correction_of·recovery_of)은 엔티티 매핑이 있어 ddl-auto=update가 만들지만, 이
 * 러너가 없거나 생성 컬럼이 참조할 컬럼이 없으면 부팅을 실패시켜 스키마 불일치를 조용히 지나치지 않는다
 * (PaymentItemConstraintMigrationRunner와 동일한 "마커와 실제 스키마가 다르면 부팅 실패" 규약).
 *
 * <p><b>운영 주의(락)</b>: nullable base 컬럼 3개는 MySQL 8.0에서 INSTANT ADD지만, <b>stored 생성 컬럼
 * {@code active_reservation_id} 추가는 INSTANT가 아니라 테이블 리빌드</b>(COPY/INPLACE)를 유발하고 UNIQUE 인덱스
 * 빌드도 동반한다. 현재 규모(예약당 1행 수준)에서는 배포 창 안에 끝나지만, {@code payments}가 커지면 이 부팅 시
 * 마이그레이션이 쓰기를 오래 막을 수 있다 — 대형화 시 pt-online-schema-change/gh-ost 등 온라인 DDL로 분리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentActiveConstraintMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "payment_active_reservation_unique_v1";
    static final String TABLE = "payments";
    static final String GENERATED_COLUMN = "active_reservation_id";
    static final String ACTIVE_UNIQUE_INDEX = "uk_payments_active_reservation_id";
    static final String LEGACY_UNIQUE_INDEX = "uk_payments_reservation_id";
    static final String CHAIN_EXCLUSIVE_CHECK = "chk_payments_recovery_correction_exclusive";
    private static final String LOCK_NAME = "doctorpet:payment_active_reservation_unique_v1";
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
        if (migrationApplied(connection)) {
            assertSchema(connection);
            return;
        }

        // base 컬럼(방어적). 엔티티 매핑이 있어 ddl-auto가 먼저 만들지만, ddl-auto가 꺼진 환경에서도 생성 컬럼이
        // 참조할 대상이 있게 보장한다. 셀프 복구·정정 재청구 체인 컬럼은 nullable이며 기본 NULL(활성)이다.
        if (!columnExists(connection, "superseded_at")) {
            execute(connection, "alter table payments add column superseded_at datetime null");
        }
        if (!columnExists(connection, "correction_of")) {
            execute(connection, "alter table payments add column correction_of bigint null");
        }
        if (!columnExists(connection, "recovery_of")) {
            execute(connection, "alter table payments add column recovery_of bigint null");
        }

        // 활성일 때만 reservation_id를 내보내는 생성 컬럼. 대체된 과거 결제는 NULL이라 UNIQUE 중복이 아니게 되어
        // 이력으로 남으면서도 "활성 1건"이 지켜진다.
        if (!columnExists(connection, GENERATED_COLUMN)) {
            execute(connection, """
                    alter table payments
                    add column active_reservation_id bigint
                    generated always as (
                        case when superseded_at is null then reservation_id else null end
                    ) stored
                    """);
        }

        // expand: 새 UNIQUE 먼저 생성·검증.
        if (!activeUniqueIndexExists(connection)) {
            if (indexNameExists(connection, ACTIVE_UNIQUE_INDEX)) {
                throw new IllegalStateException("활성 결제 UNIQUE 인덱스 구성이 올바르지 않습니다.");
            }
            execute(connection, """
                    create unique index uk_payments_active_reservation_id
                    on payments (active_reservation_id)
                    """);
        }
        if (!activeUniqueIndexExists(connection)) {
            throw new IllegalStateException("활성 결제 UNIQUE 인덱스 생성에 실패했습니다.");
        }

        // 한 결제가 정정·복구 체인을 동시에 가질 수 없다(3.3·3.5-a는 상호 배타). ddl-auto는 CHECK를 붙이지 않으므로 여기서 붙인다.
        if (!checkConstraintExists(connection, CHAIN_EXCLUSIVE_CHECK)) {
            execute(connection, """
                    alter table payments
                    add constraint chk_payments_recovery_correction_exclusive
                    check (not (recovery_of is not null and correction_of is not null))
                    """);
        }

        // contract: 새 UNIQUE가 검증된 뒤에만 구 UNIQUE 제거. 신규 DB에는 애초에 없어(엔티티에서 선언 제거) no-op이다.
        if (indexNameExists(connection, LEGACY_UNIQUE_INDEX)) {
            execute(connection, "alter table payments drop index uk_payments_reservation_id");
        }

        assertSchema(connection);
        recordMigration(connection);
        log.info("활성 결제 UNIQUE 제약 적용 완료: {} (구 {} 제거)", ACTIVE_UNIQUE_INDEX, LEGACY_UNIQUE_INDEX);
    }

    private void assertSchema(Connection connection) throws SQLException {
        if (!columnExists(connection, "superseded_at")
                || !columnExists(connection, "correction_of")
                || !columnExists(connection, "recovery_of")) {
            throw new IllegalStateException("활성 결제 체인 컬럼이 없습니다.");
        }
        if (!generatedColumnExists(connection)) {
            throw new IllegalStateException("활성 결제 생성 컬럼(active_reservation_id)이 없습니다.");
        }
        if (!activeUniqueIndexExists(connection)) {
            throw new IllegalStateException("활성 결제 UNIQUE 인덱스가 없습니다.");
        }
        if (!checkConstraintExists(connection, CHAIN_EXCLUSIVE_CHECK)) {
            throw new IllegalStateException("정정·복구 체인 배타 CHECK 제약이 없습니다.");
        }
        if (indexNameExists(connection, LEGACY_UNIQUE_INDEX)) {
            throw new IllegalStateException(
                    "구 UNIQUE(uk_payments_reservation_id)가 남아 있어 재청구가 막힙니다. 제거되어야 합니다.");
        }
    }

    private boolean generatedColumnExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'payments'
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
                   and table_name = 'payments'
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
                   and table_name = 'payments'
                   and index_name = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    /** 이름만 같은 복합 UNIQUE가 아니라, active_reservation_id 단일 컬럼 UNIQUE인지까지 확인한다. */
    private boolean activeUniqueIndexExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_name
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payments'
                   and index_name = ?
                   and non_unique = 0
                 order by seq_in_index
                """)) {
            statement.setString(1, ACTIVE_UNIQUE_INDEX);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && GENERATED_COLUMN.equals(resultSet.getString(1))
                        && !resultSet.next();
            }
        }
    }

    private boolean checkConstraintExists(Connection connection, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.table_constraints
                 where table_schema = database()
                   and table_name = 'payments'
                   and constraint_name = ?
                   and constraint_type = 'CHECK'
                """)) {
            statement.setString(1, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
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
                    throw new IllegalStateException("활성 결제 UNIQUE 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("활성 결제 UNIQUE 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
