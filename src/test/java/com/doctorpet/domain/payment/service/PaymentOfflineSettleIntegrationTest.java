package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 오프라인 정산 통합·동시성 검증(STRICT, #36, SA §5·§9-4). 실 MySQL에 저장된 OFFLINE_REQUIRED
 * 결제를 대상으로 조건부 UPDATE 전이·감사 기록·멱등·동시 정산 1건만 성립을 확인한다. 예약·스태프 port는 목,
 * 알림 발행 수로 실제 정산 횟수를 검증한다. 전체 컨텍스트(MySQL·Redis·env) 필요.
 */
@SpringBootTest
class PaymentOfflineSettleIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 20;
    private static final Long HOSPITAL_ID = 8888L;
    private static final Long STAFF_MEMBER_ID = 91L;
    private static final Long GUARDIAN_ID = 51L;

    @Autowired private PaymentOfflineSettlementService paymentOfflineSettlementService;
    @Autowired private PaymentRepository paymentRepository;

    @MockitoBean private ReservationLookupPort reservationLookupPort;
    @MockitoBean private StaffHospitalPort staffHospitalPort;
    @MockitoBean private PaymentNotificationPublisher notificationPublisher;

    private final List<Long> paymentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
    }

    @AfterEach
    void tearDown() {
        paymentIds.forEach(paymentRepository::deleteById);
    }

    @Test
    @DisplayName("OFFLINE_REQUIRED 정산은 OFFLINE_PAID·OFFLINE 채널·처리자/시각 감사를 영속하고 알림을 1회 발행한다")
    void settle_persistsAndAudits() {
        Long paymentId = persistPayment(true);

        PaymentHistoryResponse response = paymentOfflineSettlementService.settle(paymentId, STAFF_MEMBER_ID);

        assertThat(response.status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        assertThat(response.paymentChannel()).isEqualTo(PaymentChannel.OFFLINE);

        Payment settled = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        assertThat(settled.getPaymentChannel()).isEqualTo(PaymentChannel.OFFLINE);
        assertThat(settled.getOfflineSettledBy()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(settled.getOfflineSettledAt()).isNotNull();
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), anyLong(), eq(paymentId), eq(PaymentStatus.OFFLINE_PAID));
    }

    @Test
    @DisplayName("반복 정산 요청은 멱등하다 — 두 번째 호출은 상태를 바꾸지 않고 알림도 다시 발행하지 않는다")
    void repeat_isIdempotent() {
        Long paymentId = persistPayment(true);

        paymentOfflineSettlementService.settle(paymentId, STAFF_MEMBER_ID);
        PaymentHistoryResponse second = paymentOfflineSettlementService.settle(paymentId, STAFF_MEMBER_ID);

        assertThat(second.status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        verify(notificationPublisher, times(1)).publishChargeResult(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("OFFLINE_REQUIRED가 아닌 결제(PENDING) 정산은 409(OFFLINE_PRECONDITION_FAILED)")
    void notOfflineRequired_precondition() {
        Long paymentId = persistPayment(false);

        assertThatThrownBy(() -> paymentOfflineSettlementService.settle(paymentId, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.OFFLINE_PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("같은 결제에 동시 정산이 몰려도 실제 정산은 1건만 성립하고(알림 1회) 나머지는 충돌(409)로 거부된다")
    void concurrentSettle_onlyOneSettles() throws InterruptedException {
        Long paymentId = persistPayment(true);
        // 동시 정산에서 패자는 자기 트랜잭션 스냅샷상 승자의 커밋을 못 봐 OFFLINE_PRECONDITION_FAILED(409)를 받는다.
        // 이는 정상적인 동시성 충돌 결과이며(순차 반복은 새 트랜잭션이라 멱등 200), 그 외 예외만 실패로 본다.
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    paymentOfflineSettlementService.settle(paymentId, STAFF_MEMBER_ID);
                } catch (ServiceException e) {
                    if (e.getErrorCode() != PaymentErrorCode.OFFLINE_PRECONDITION_FAILED) {
                        unexpected.add(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    unexpected.add(e);
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
        // 정산 충돌(409) 외의 예외는 없어야 한다.
        assertThat(unexpected).isEmpty();
        // 실제 정산(알림 발행)은 정확히 1회여야 한다 — 중복 정산 없음.
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), anyLong(), eq(paymentId), eq(PaymentStatus.OFFLINE_PAID));
        Payment settled = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        assertThat(settled.getOfflineSettledBy()).isEqualTo(STAFF_MEMBER_ID);
    }

    /** OFFLINE_REQUIRED(true) 또는 PENDING(false) 결제를 저장하고 예약 port를 스텁한다. */
    private Long persistPayment(boolean offlineRequired) {
        long reservationId = System.nanoTime();
        Payment payment = Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", 50_000);
        if (offlineRequired) {
            payment.markOfflineRequired("NON_RETRIABLE", 0);
        }
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        paymentIds.add(paymentId);
        given(reservationLookupPort.findForCharge(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, 7L, true)));
        return paymentId;
    }
}
