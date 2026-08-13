package com.doctorpet.domain.payment.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * payment_items의 항목 불변식을 MySQL에서 보장한다(고도화 결제 3.1). {@code ddl-auto=update}는 테이블·인덱스는
 * 만들어도 CHECK 제약을 붙여주지 않으므로, 수량 양수와 "항목 금액 = 수량 × 단가"를 여기서 명시적으로 추가·검증한다.
 *
 * <p>단가·항목 금액은 할인·조정 항목(음수)을 담아야 하므로 signed여야 한다. UNSIGNED로 만들어진 컬럼이 있으면
 * 할인 항목이 저장 자체가 불가능해지므로 부팅을 중단시켜 스키마 불일치를 조용히 지나치지 않는다
 * (PaymentMethodDefaultMigrationRunner와 동일한 "마커와 실제 스키마가 다르면 부팅 실패" 규약).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentItemConstraintMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "payment_item_constraints_v1";
    static final String TABLE = "payment_items";
    static final String QUANTITY_CHECK = "chk_payment_items_quantity_positive";
    static final String LINE_AMOUNT_CHECK = "chk_payment_items_line_amount";
    static final String PAYMENT_ID_INDEX = "idx_payment_items_payment_id";
    private static final String LOCK_NAME = "doctorpet:payment_item_constraints_v1";
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

        // 컬럼이 signed인지 먼저 본다 — UNSIGNED면 CHECK를 붙여도 할인 항목을 저장할 수 없어 의미가 없다.
        assertSignedAmountColumns(connection);

        if (!checkConstraintExists(connection, QUANTITY_CHECK)) {
            execute(connection, """
                    alter table payment_items
                    add constraint chk_payment_items_quantity_positive check (quantity > 0)
                    """);
        }
        if (!checkConstraintExists(connection, LINE_AMOUNT_CHECK)) {
            // quantity·unit_price 모두 INT라 MySQL이 곱셈을 BIGINT로 계산하므로 이 비교 자체는 넘치지 않는다.
            execute(connection, """
                    alter table payment_items
                    add constraint chk_payment_items_line_amount check (amount = quantity * unit_price)
                    """);
        }
        if (!indexNameExists(connection, PAYMENT_ID_INDEX)) {
            execute(connection, """
                    create index idx_payment_items_payment_id on payment_items (payment_id)
                    """);
        }

        assertSchema(connection);
        recordMigration(connection);
        log.info("청구 항목 제약 적용 완료: {}, {}", QUANTITY_CHECK, LINE_AMOUNT_CHECK);
    }

    private void assertSchema(Connection connection) throws SQLException {
        assertSignedAmountColumns(connection);
        if (!checkConstraintExists(connection, QUANTITY_CHECK)) {
            throw new IllegalStateException("청구 항목 수량 CHECK 제약이 없습니다.");
        }
        if (!checkConstraintExists(connection, LINE_AMOUNT_CHECK)) {
            throw new IllegalStateException("청구 항목 금액 CHECK 제약이 없습니다.");
        }
        if (!indexNameExists(connection, PAYMENT_ID_INDEX)) {
            throw new IllegalStateException("청구 항목 조회 인덱스가 없습니다.");
        }
    }

    /** 할인·조정 항목을 담으려면 단가·항목 금액이 음수를 표현할 수 있어야 한다. */
    private void assertSignedAmountColumns(Connection connection) throws SQLException {
        assertSignedColumn(connection, "unit_price");
        assertSignedColumn(connection, "amount");
    }

    private void assertSignedColumn(Connection connection, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'payment_items'
                   and column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("청구 항목 컬럼이 없습니다: " + columnName);
                }
                String columnType = resultSet.getString(1);
                if (columnType != null && columnType.toLowerCase(Locale.ROOT).contains("unsigned")) {
                    throw new IllegalStateException(
                            "청구 항목 컬럼은 할인 항목을 위해 signed여야 합니다: " + columnName + " = " + columnType);
                }
            }
        }
    }

    private boolean checkConstraintExists(Connection connection, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.table_constraints
                 where table_schema = database()
                   and table_name = ?
                   and constraint_name = ?
                   and constraint_type = 'CHECK'
                """)) {
            statement.setString(1, TABLE);
            statement.setString(2, constraintName);
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
                   and table_name = ?
                   and index_name = ?
                """)) {
            statement.setString(1, TABLE);
            statement.setString(2, indexName);
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
                    throw new IllegalStateException("청구 항목 제약 마이그레이션 DB 잠금을 획득하지 못했습니다.");
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("청구 항목 제약 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
