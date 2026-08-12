package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import java.util.ArrayList;
import java.util.List;
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

/** 실제 MySQL 생성 컬럼 UNIQUE와 기본값 변경 동시성을 검증한다. */
@SpringBootTest
class PaymentMethodDefaultIntegrationTest {

    @Autowired private PaymentMethodService paymentMethodService;
    @Autowired private PaymentMethodRepository paymentMethodRepository;

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

    private PaymentMethod save(long memberId, String last4) {
        PaymentMethod paymentMethod = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(memberId, "v1:" + System.nanoTime(), "VISA", last4));
        paymentMethodIds.add(paymentMethod.getId());
        return paymentMethod;
    }
}
