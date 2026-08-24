package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 MySQL 생성 컬럼 UNIQUE와 기본값 변경 동시성을 검증한다. */
@SpringBootTest
class PaymentMethodDefaultIntegrationTest {

    @Autowired private PaymentMethodService paymentMethodService;
    @MockitoSpyBean private PaymentMethodRepository paymentMethodRepository;
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
    @DisplayName("기본값 UNIQUE는 생성 컬럼 하나만 포함한다")
    void activeDefaultUniqueConstraint_hasExactlyOneGeneratedColumn() {
        assertIndexColumns(
                "uk_payment_methods_active_default_member_id",
                0,
                "active_default_member_id"
        );
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
        CountDownLatch bothExistChecksEntered = new CountDownLatch(2);
        CountDownLatch releaseExistChecks = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Long> registeredIds = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            bothExistChecksEntered.countDown();
            assertThat(releaseExistChecks.await(5, TimeUnit.SECONDS)).isTrue();
            // Repository 인터페이스의 구현체가 아니라 반환값만 제어한다. 이후 두 save는 실제
            // MySQL에서 실행되어 생성 컬럼 UNIQUE 충돌과 fallback 저장을 검증한다.
            return false;
        }).when(paymentMethodRepository).existsByMemberIdAndStatus(eq(memberId), eq(PaymentMethodStatus.ACTIVE));

        try {
            executor.submit(() -> registerAfterBarrier(
                    memberId, "billing-key-first", registeredIds, failures, done));
            executor.submit(() -> registerAfterBarrier(
                    memberId, "billing-key-second", registeredIds, failures, done));
            assertThat(bothExistChecksEntered.await(5, TimeUnit.SECONDS)).isTrue();
            releaseExistChecks.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseExistChecks.countDown();
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
    @DisplayName("기본값 변경의 비관적 잠금 조회 인덱스는 member_id, status만 포함한다")
    void activePaymentMethodLockQuery_hasExactlyMemberStatusIndex() {
        assertIndexColumns(
                "idx_payment_methods_member_id_status",
                1,
                "member_id",
                "status"
        );
    }

    private void assertIndexColumns(String indexName, int nonUnique, String... expectedColumns) {
        List<String> indexColumns = jdbcTemplate.queryForList("""
                select column_name
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'payment_methods'
                   and index_name = ?
                   and non_unique = ?
                 order by seq_in_index
                """, String.class, indexName, nonUnique);

        assertThat(indexColumns).containsExactly(expectedColumns);
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

    private void registerAfterBarrier(
            long memberId,
            String billingKey,
            List<Long> registeredIds,
            List<Throwable> failures,
            CountDownLatch done
    ) {
        try {
            registeredIds.add(paymentMethodService.register(
                    memberId,
                    new PaymentMethodRegisterRequest(billingKey)).id());
        } catch (Throwable throwable) {
            failures.add(throwable);
        } finally {
            done.countDown();
        }
    }

    private PaymentMethod save(long memberId, String last4) {
        PaymentMethod paymentMethod = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(memberId, "v1:" + System.nanoTime(), "VISA", last4));
        paymentMethodIds.add(paymentMethod.getId());
        return paymentMethod;
    }
}
