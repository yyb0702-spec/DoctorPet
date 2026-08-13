package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 MySQL 생성 컬럼 UNIQUE와 기본값 변경 동시성을 검증한다. */
@SpringBootTest
class PaymentMethodDefaultIntegrationTest {

    @Autowired private PaymentMethodService paymentMethodService;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> paymentMethodIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        paymentMethodIds.forEach(paymentMethodRepository::deleteById);
    }

    @Test
    @DisplayName("MySQL 생성 컬럼 UNIQUE는 한 회원의 ACTIVE 기본 결제수단 중복을 거부한다")
    void activeDefaultUniqueConstraint_rejectsDuplicate() {
        long memberId = System.nanoTime();
        PaymentMethod first = save(memberId, "1111");
        PaymentMethod second = save(memberId, "2222");
        first.markDefault();
        paymentMethodRepository.saveAndFlush(first);
        second.markDefault();

        assertThatThrownBy(() -> paymentMethodRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("동시 기본값 변경 후에도 ACTIVE 기본 결제수단은 정확히 한 건이다")
    void concurrentSetDefault_keepsExactlyOneDefault() throws Exception {
        long memberId = System.nanoTime();
        PaymentMethod first = save(memberId, "1111");
        PaymentMethod second = save(memberId, "2222");
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Throwable> failures = new ArrayList<>();

        executor.submit(() -> setDefaultAfterStart(memberId, first.getId(), ready, start, done, failures));
        executor.submit(() -> setDefaultAfterStart(memberId, second.getId(), ready, start, done, failures));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(failures).isEmpty();
        long defaultCount = paymentMethodRepository
                .findByMemberIdAndStatusOrderByCreatedAtDesc(
                        memberId,
                        com.doctorpet.domain.payment.entity.PaymentMethodStatus.ACTIVE
                )
                .stream()
                .filter(PaymentMethod::isDefaultPaymentMethod)
                .count();
        assertThat(defaultCount).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 활성 카드가 있고 기본값이 비어 있는 회원의 신규 등록은 기본값이 아니다")
    void register_withExistingActivePaymentMethod_doesNotAssignDefault() {
        long memberId = System.nanoTime();
        save(memberId, "1111");

        var response = paymentMethodService.register(
                memberId,
                new PaymentMethodRegisterRequest("billing-key"));
        paymentMethodIds.add(response.id());

        assertThat(response.isDefault()).isFalse();
        long defaultCount = paymentMethodRepository
                .findByMemberIdAndStatusOrderByCreatedAtDesc(
                        memberId,
                        com.doctorpet.domain.payment.entity.PaymentMethodStatus.ACTIVE
                )
                .stream()
                .filter(PaymentMethod::isDefaultPaymentMethod)
                .count();
        assertThat(defaultCount).isZero();
    }

    @Test
    @DisplayName("동시 최초 등록은 UNIQUE 충돌 후 재시도로 두 건을 저장하고 기본값을 한 건만 남긴다")
    void concurrentFirstRegistration_persistsBothAndKeepsExactlyOneDefault() throws Exception {
        long memberId = System.nanoTime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Long> registeredIds = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try (Connection blocker = jdbcTemplate.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            insertUncommittedDefaultPaymentMethod(blocker, memberId);
            executor.submit(() -> registerAfterStart(
                    memberId, "billing-key-first", registeredIds, failures, ready, start, done));
            executor.submit(() -> registerAfterStart(
                    memberId, "billing-key-second", registeredIds, failures, ready, start, done));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            // 두 요청은 미커밋 행을 볼 수 없어 모두 exists=false를 반환하지만, 기본값 INSERT는
            // 같은 UNIQUE 키에서 대기한다. 이 잠금 대기로 fallback 경로의 전제 조건을 실제 DB에서 고정한다.
            assertThat(waitForInnoDbLockWaiters(2)).isTrue();
            blocker.rollback();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        paymentMethodIds.addAll(registeredIds);
        assertThat(failures).isEmpty();
        List<PaymentMethod> activeMethods = paymentMethodRepository
                .findByMemberIdAndStatusOrderByCreatedAtDesc(memberId, PaymentMethodStatus.ACTIVE);
        assertThat(activeMethods).hasSize(2);
        assertThat(activeMethods.stream().filter(PaymentMethod::isDefaultPaymentMethod)).hasSize(1);
    }

    @Test
    @DisplayName("기본값 변경의 비관적 잠금 조회에 member_id, status 복합 인덱스가 존재한다")
    void activePaymentMethodLockQuery_hasMemberStatusIndex() {
        Integer indexColumnCount = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and index_name = 'idx_payment_methods_member_id_status'
                   and non_unique = 1
                   and ((seq_in_index = 1 and column_name = 'member_id')
                        or (seq_in_index = 2 and column_name = 'status'))
                """, Integer.class);

        assertThat(indexColumnCount).isEqualTo(2);
    }

    private void setDefaultAfterStart(
            long memberId,
            Long paymentMethodId,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            List<Throwable> failures
    ) {
        try {
            ready.countDown();
            start.await();
            paymentMethodService.setDefault(memberId, paymentMethodId);
        } catch (Throwable throwable) {
            synchronized (failures) {
                failures.add(throwable);
            }
        } finally {
            done.countDown();
        }
    }

    private void registerAfterStart(
            long memberId,
            String billingKey,
            List<Long> registeredIds,
            List<Throwable> failures,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done
    ) {
        try {
            ready.countDown();
            start.await();
            registeredIds.add(paymentMethodService.register(
                    memberId,
                    new PaymentMethodRegisterRequest(billingKey)).id());
        } catch (Throwable throwable) {
            failures.add(throwable);
        } finally {
            done.countDown();
        }
    }

    private void insertUncommittedDefaultPaymentMethod(Connection connection, long memberId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into payment_methods
                        (member_id, billing_key_enc, card_brand, card_last4, status, is_default, created_at, updated_at)
                values (?, ?, 'VISA', '0000', 'ACTIVE', true, ?, ?)
                """)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setLong(1, memberId);
            statement.setString(2, "v1:uncommitted-" + memberId);
            statement.setObject(3, now);
            statement.setObject(4, now);
            statement.executeUpdate();
        }
    }

    private boolean waitForInnoDbLockWaiters(int expectedWaiterCount) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Integer waiterCount = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.innodb_lock_waits", Integer.class);
            if (waiterCount != null && waiterCount >= expectedWaiterCount) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private PaymentMethod save(long memberId, String last4) {
        PaymentMethod paymentMethod = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(memberId, "v1:" + System.nanoTime(), "VISA", last4));
        paymentMethodIds.add(paymentMethod.getId());
        return paymentMethod;
    }
}
