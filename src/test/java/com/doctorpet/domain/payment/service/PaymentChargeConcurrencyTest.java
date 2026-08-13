package com.doctorpet.domain.payment.service;

import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.deleteItems;
import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.persistDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 진료비 청구 동시성 통합 검증(STRICT, SA §9-4·부록 B). 같은 예약에 동시 청구가 몰려도
 * UNIQUE(reservation_id) + PENDING 선기록으로 정확히 1건만 성립하는지 실제 MySQL로 검증한다.
 * Mockito 슬라이스로는 DB UNIQUE 경쟁 자체를 확인할 수 없다. 예약·스태프 port는 목으로 주입하고
 * 게이트웨이는 fake다. 전체 컨텍스트(MySQL·Redis·fake gateway·JWT/enc-key env)가 필요하다 —
 * 없으면 BLOCKED. (기존 AuthServiceConcurrencyTest와 동일한 인프라 전제.)
 */
@SpringBootTest
class PaymentChargeConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 20;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;
    private static final int AMOUNT = 50_000;

    @Autowired private PaymentApplicationService paymentApplicationService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentItemRepository paymentItemRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;

    @MockitoBean private ReservationLookupPort reservationLookupPort;
    @MockitoBean private StaffHospitalPort staffHospitalPort;

    private Long reservationId;
    private Long paymentMethodId;

    @BeforeEach
    void setUp() {
        // 예약마다 유일한 id로 다른 테스트 실행과 충돌을 피한다.
        reservationId = System.nanoTime();
        paymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(GUARDIAN_ID, billingKeyCryptor.encrypt("test-billing-key"), "VISA", "1234")).getId();

        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForChargeForUpdate(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, paymentMethodId, true)));

        // 청구는 초안 항목 합계로 총액을 산출하므로 초안을 먼저 깐다(SA §9-4 청구 항목).
        persistDraft(paymentItemRepository, reservationId, AMOUNT);
    }

    @AfterEach
    void tearDown() {
        // 커밋된 테스트 데이터를 정리해 영속 볼륨에 잔여가 쌓이지 않게 한다.
        Long paymentId = paymentRepository.findByReservationId(reservationId)
                .map(payment -> {
                    Long id = payment.getId();
                    paymentRepository.delete(payment);
                    return id;
                })
                .orElse(null);
        deleteItems(paymentItemRepository, reservationId, paymentId);
        paymentMethodRepository.deleteById(paymentMethodId);
    }

    @Test
    @DisplayName("같은 예약에 동시 청구가 몰려도 정확히 1건만 성립하고 나머지는 DUPLICATE_CHARGE로 거부된다")
    void concurrentCharge_onlyOneSucceeds() throws InterruptedException {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger unexpectedFailure = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    PaymentChargeResponse response =
                            paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID);
                    if (response.status() == PaymentStatus.PAID) {
                        success.incrementAndGet();
                    }
                } catch (ServiceException e) {
                    // 승자 외에는 UNIQUE(reservation_id) 경쟁으로 DUPLICATE_CHARGE(409)를 받아야 한다.
                    // 다른 오류(INVALID_AMOUNT·PAYMENT_METHOD_NOT_FOUND 등)를 중복으로 오집계하지 않도록 구분한다.
                    if (e.getErrorCode() == PaymentErrorCode.DUPLICATE_CHARGE) {
                        duplicate.incrementAndGet();
                    } else {
                        unexpectedFailure.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(success.get()).isEqualTo(1);
        // 나머지는 모두 DUPLICATE_CHARGE여야 하며, 엉뚱한 실패(unexpectedFailure)는 0이어야 한다.
        assertThat(unexpectedFailure.get()).isZero();
        assertThat(duplicate.get()).isEqualTo(CONCURRENT_REQUESTS - 1);
        // 예약당 결제 레코드는 정확히 1건만 존재한다.
        assertThat(paymentRepository.findByReservationId(reservationId)).isPresent();
    }
}
