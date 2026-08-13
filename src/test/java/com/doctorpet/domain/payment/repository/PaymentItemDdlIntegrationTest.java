package com.doctorpet.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.payment.entity.PaymentItem;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — payment_items DDL·제약 통합 검증(고도화 결제 3.1, STRICT: 스키마).
 * PaymentItemConstraintMigrationRunner가 실제로 적용한 CHECK 제약·인덱스와, 단가·금액 컬럼이 signed인지를
 * 실제 MySQL에 연결해 확인한다. ddl-auto=update는 CHECK를 붙여주지 않으므로 러너가 도는 전체 컨텍스트가 필요하다
 * (@DataJpaTest 슬라이스는 ApplicationRunner를 실행하지 않아 이 검증을 할 수 없다).
 */
@SpringBootTest
class PaymentItemDdlIntegrationTest {

    @Autowired private PaymentItemRepository paymentItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long paymentId;

    @AfterEach
    void tearDown() {
        if (paymentId != null) {
            jdbcTemplate.update("delete from payment_items where payment_id = ?", paymentId);
        }
    }

    @Test
    @DisplayName("단가·항목 금액 컬럼은 UNSIGNED가 아니다(할인·조정 항목의 음수 저장 전제)")
    void priceColumns_areSigned() {
        assertThat(columnType("unit_price")).doesNotContain("unsigned");
        assertThat(columnType("amount")).doesNotContain("unsigned");
    }

    @Test
    @DisplayName("음수 단가·음수 금액 항목이 실제로 저장·조회된다")
    void negativeAmountItem_persists() {
        paymentId = System.nanoTime();
        paymentItemRepository.saveAndFlush(
                PaymentItem.snapshot(paymentId, "재진 할인", 1, -5_000, -5_000));

        List<PaymentItem> items = paymentItemRepository.findByPaymentIdOrderByIdAsc(paymentId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getUnitPrice()).isEqualTo(-5_000);
        assertThat(items.get(0).getAmount()).isEqualTo(-5_000);
    }

    @Test
    @DisplayName("수량 CHECK 제약이 존재하고, 0 이하 수량 삽입을 DB가 거부한다")
    void quantityCheckConstraint_rejectsNonPositive() {
        assertThat(checkConstraintExists("chk_payment_items_quantity_positive")).isTrue();

        // MySQL CHECK 위반은 벤더 코드 3819/SQLState HY000이라 Spring이 DataIntegrityViolationException이
        // 아니라 UncategorizedSQLException으로 번역한다. 예외 타입이 아니라 "어느 제약이 막았는지"로 확인한다.
        assertThatThrownBy(() -> insertRaw(System.nanoTime(), "잘못된 수량", 0, 10_000, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_payment_items_quantity_positive");
    }

    @Test
    @DisplayName("항목 금액 CHECK 제약이 존재하고, 수량×단가와 다른 금액 삽입을 DB가 거부한다")
    void lineAmountCheckConstraint_rejectsMismatch() {
        assertThat(checkConstraintExists("chk_payment_items_line_amount")).isTrue();

        assertThatThrownBy(() -> insertRaw(System.nanoTime(), "금액 불일치", 2, 10_000, 30_000))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_payment_items_line_amount");
    }

    @Test
    @DisplayName("payment_id 조회 인덱스가 payment_id 단일 컬럼으로 존재한다")
    void paymentIdIndex_exists() {
        List<String> columns = jdbcTemplate.queryForList("""
                select column_name
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payment_items'
                   and index_name = 'idx_payment_items_payment_id'
                 order by seq_in_index
                """, String.class);

        assertThat(columns).containsExactly("payment_id");
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject("""
                select lower(column_type)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'payment_items'
                   and column_name = ?
                """, String.class, columnName);
    }

    private boolean checkConstraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.table_constraints
                 where table_schema = database()
                   and table_name = 'payment_items'
                   and constraint_name = ?
                   and constraint_type = 'CHECK'
                """, Integer.class, constraintName);
        return count != null && count > 0;
    }

    /** CHECK 제약이 실제로 거부하는지 보려면 엔티티 검증을 우회해야 하므로 raw SQL로 삽입한다. */
    private void insertRaw(long paymentId, String name, int quantity, int unitPrice, int amount) {
        jdbcTemplate.update("""
                insert into payment_items (payment_id, name, quantity, unit_price, amount, created_at, updated_at)
                values (?, ?, ?, ?, ?, now(), now())
                """, paymentId, name, quantity, unitPrice, amount);
    }
}
