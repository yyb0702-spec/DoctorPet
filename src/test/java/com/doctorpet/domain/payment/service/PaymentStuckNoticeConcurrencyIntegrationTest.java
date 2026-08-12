package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Level 3 — "결제 확인 중" 안내(STUCK)와 결제 확정(PAID)의 경합 순서를 실 MySQL로 검증한다(PR #139 리뷰 P1).
 * publishStuckNoticeIfPending은 결제 행을 잠그고 현재 PENDING인지 재확인한 뒤에만 안내를 저장하므로, 확정을
 * 원자화하는 조건부 UPDATE(markPaidIfPending)와 같은 행 락에 직렬화된다. 따라서 "완료 알림 뒤에 뒤늦은 확인 중"
 * 순서(완료→확인중)는 발생하지 않는다 — 두 알림이 모두 남는 경우 확인 중이 항상 먼저 삽입된다(id가 더 작다).
 * 실 MySQL·Redis·env 필요(로컬 DB 미가동 시 BLOCKED).
 */
@SpringBootTest
class PaymentStuckNoticeConcurrencyIntegrationTest {

    private static final Long HOSPITAL_ID = 6161L;
    // 다른 통합 테스트와 알림이 섞이지 않도록 이 테스트 전용 수신자 id를 쓴다.
    private static final Long GUARDIAN_ID = 913_913_913L;
    private static final int AMOUNT = 50_000;

    @Autowired private PaymentReconcileService reconcileService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private NotificationRepository notificationRepository;
    // 실제 저장형 발행 빈(StoringPaymentNotificationPublisher)을 그대로 쓴다 — 알림이 notifications에 실제로 저장돼야
    // 순서를 검증할 수 있다.
    @Autowired private PaymentNotificationPublisher notificationPublisher;
    @Autowired private TransactionTemplate transactionTemplate;

    // 예약 조회만 목으로 둔다(보호자 해석). 나머지는 실 빈.
    @MockitoBean private ReservationLookupPort reservationLookupPort;

    @RepeatedTest(20)
    @DisplayName("STUCK 안내와 PAID 확정이 동시에 일어나도 완료 알림 뒤에 뒤늦은 '확인 중'이 저장되지 않는다")
    void stuckNoticeNeverPersistedAfterPaymentConfirmed() throws Exception {
        long reservationId = System.nanoTime();
        Payment saved = paymentRepository.saveAndFlush(
                Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", AMOUNT));
        Long paymentId = saved.getId();
        given(reservationLookupPort.findForCharge(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, 7L, true)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // A: STUCK "결제 확인 중" 안내(행 락 아래 현재 PENDING이면 발행).
            Future<?> stuck = pool.submit(() -> {
                start.await();
                reconcileService.publishStuckNoticeIfPending(paymentId);
                return null;
            });
            // B: 결제 확정(조건부 UPDATE로 PENDING→PAID) 후 완료 알림 발행 — 실제 확정 경로와 같은 순서.
            Future<?> confirm = pool.submit(() -> {
                start.await();
                boolean applied = transactionTemplate.execute(status -> paymentRepository.markPaidIfPending(
                        paymentId, "PG-" + paymentId, LocalDateTime.now(), LocalDateTime.now()) > 0);
                if (Boolean.TRUE.equals(applied)) {
                    notificationPublisher.publishChargeResult(
                            GUARDIAN_ID, reservationId, paymentId, PaymentStatus.PAID, AMOUNT);
                }
                return null;
            });
            start.countDown();
            stuck.get();
            confirm.get();
        } finally {
            pool.shutdownNow();
        }

        List<Notification> forPayment = notificationRepository
                .findByMemberId(GUARDIAN_ID, PageRequest.of(0, 50))
                .getContent()
                .stream()
                .filter(n -> paymentId.equals(n.getResourceId()))
                .toList();
        Optional<Notification> pending = forPayment.stream()
                .filter(n -> n.getType() == NotificationType.PAYMENT_PENDING).findFirst();
        Optional<Notification> result = forPayment.stream()
                .filter(n -> n.getType() == NotificationType.PAYMENT_RESULT).findFirst();

        // 두 알림이 모두 남았다면, 확인 중이 완료보다 먼저 삽입돼야 한다(id는 삽입 순서로 단조 증가).
        // 행 락이 없으면 "완료 뒤 확인 중"(pending.id > result.id)이 가능해져 이 단언이 깨진다.
        if (pending.isPresent() && result.isPresent()) {
            assertThat(pending.get().getId()).isLessThan(result.get().getId());
        }

        notificationRepository.deleteAll(forPayment);
        paymentRepository.deleteById(paymentId);
    }
}
