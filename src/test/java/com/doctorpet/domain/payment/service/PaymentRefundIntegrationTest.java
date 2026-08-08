package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentRefund;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.RefundStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRefundRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.payment.repository.PaymentWebhookRepository;
import com.doctorpet.domain.payment.webhook.PaymentWebhookService;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.review.entity.Review;
import com.doctorpet.domain.review.dto.request.ReviewRequest;
import com.doctorpet.domain.review.exception.ReviewErrorCode;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.review.service.ReviewApplicationService;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.fake.FakePaymentGateway;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 진료비 환불 통합·동시성 검증(STRICT, #37, SA §5·§9-4). 실 MySQL에 저장된 PAID 결제를 대상으로
 * 조건부 UPDATE 전이·환불 이력 기록·멱등·재시도·동시 환불 1건만 성립을 확인한다. PG는 Fake로,
 * 예약·스태프 port는 목으로 두고, "PG가 실제로 몇 번 취소됐는지"는 FakePaymentGateway의 호출 수로 검증한다.
 * 전체 컨텍스트(MySQL·Redis·env) 필요.
 */
@SpringBootTest
class PaymentRefundIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 20;
    private static final Long HOSPITAL_ID = 7777L;
    private static final Long OTHER_HOSPITAL_ID = 7778L;
    private static final Long STAFF_MEMBER_ID = 71L;
    private static final Long OTHER_STAFF_MEMBER_ID = 72L;
    private static final Long GUARDIAN_ID = 41L;
    private static final int AMOUNT = 50_000;
    private static final String REASON = "진료비 오청구";

    @Autowired private PaymentRefundService paymentRefundService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private FakePaymentGateway fakePaymentGateway;
    // OFFLINE_PAID 픽스처를 실제 정산 경로(#36)로 만들기 위해서만 쓴다.
    @Autowired private PaymentOfflineSettleTxService paymentOfflineSettleTxService;
    // 환불 확정 이후 PG 취소 웹훅이 상태를 덮어쓰지 않는지 실제 경로로 확인하기 위해 쓴다(목이 아니다).
    @Autowired private PaymentWebhookService paymentWebhookService;
    @Autowired private PaymentWebhookRepository paymentWebhookRepository;
    // 환불 후 재청구가 막히는 의도된 한계를 실제 청구 경로로 확인하기 위해서만 쓴다.
    @Autowired private PaymentChargeService paymentChargeService;
    // 선점 시각(claimed_at)을 운영과 같은 서울 기준으로 만들기 위해 주입한다.
    @Autowired private java.time.Clock clock;
    // 소유권 펜스를 직접 검증하기 위해 Tx 경계를 직접 호출한다.
    @Autowired private PaymentRefundTxService paymentRefundTxService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private ReviewApplicationService reviewApplicationService;

    @MockitoBean private ReservationLookupPort reservationLookupPort;
    @MockitoBean private StaffHospitalPort staffHospitalPort;
    @MockitoBean private PaymentNotificationPublisher notificationPublisher;

    private final List<Long> paymentIds = new ArrayList<>();
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<String> createdWebhookIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(staffHospitalPort.findHospitalIdByMemberId(OTHER_STAFF_MEMBER_ID))
                .willReturn(Optional.of(OTHER_HOSPITAL_ID));
    }

    @AfterEach
    void tearDown() {
        // 이력·웹훅이 결제를 참조하므로 결제보다 먼저 지운다.
        createdWebhookIds.forEach(id -> paymentWebhookRepository.findByWebhookId(id)
                .ifPresent(paymentWebhookRepository::delete));
        paymentIds.forEach(id -> paymentRefundRepository.findByPaymentId(id)
                .ifPresent(paymentRefundRepository::delete));
        paymentIds.forEach(paymentRepository::deleteById);
        reservationIds.forEach(reviewRepository::deleteByReservationId);
        reservationIds.forEach(reservationRepository::deleteById);
        fakePaymentGateway.reset();
    }

    @Test
    @DisplayName("PAID 환불은 REFUNDED·환불 시각을 영속하고 이력에 사유·처리자·PG 취소 식별자를 남기며 알림을 1회 발행한다")
    void refund_persistsAndAudits() {
        Long paymentId = persistPayment(PaymentStatus.PAID);

        PaymentHistoryResponse response = paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(response.refundedAt()).isNotNull();

        Payment refunded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.getRefundedAt()).isNotNull();
        // 채널은 결제 시점 값을 유지한다 — 어떤 수단으로 결제된 건을 되돌렸는지가 이력으로 남아야 한다.
        assertThat(refunded.getPaymentChannel()).isEqualTo(PaymentChannel.BILLING_KEY);
        // JPQL bulk UPDATE는 @LastModifiedDate를 우회하므로 updatedAt도 환불 시각으로 명시 갱신됐는지 확인한다.
        assertThat(refunded.getUpdatedAt()).isEqualTo(refunded.getRefundedAt());

        PaymentRefund refund = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getAmount()).isEqualTo(AMOUNT);
        assertThat(refund.getReason()).isEqualTo(REASON);
        assertThat(refund.getRefundedBy()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(refund.getPgCancelId()).isNotBlank();
        assertThat(refund.getRefundedAt()).isNotNull();

        assertThat(fakePaymentGateway.cancelCallCount()).isEqualTo(1);
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), anyLong(), eq(paymentId), eq(PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("환불 확정은 리뷰를 Hard Delete하고 예약의 리뷰 작성권을 초기화한다")
    void refund_deletesReviewAndResetsReviewedAt() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        Long reservationId = paymentRepository.findById(paymentId).orElseThrow()
                .getReservationId();
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow();
        reservation.markReviewed(LocalDateTime.now(clock));
        reservationRepository.saveAndFlush(reservation);
        reviewRepository.saveAndFlush(Review.create(
                reservationId,
                HOSPITAL_ID,
                GUARDIAN_ID,
                new BigDecimal("4.5"),
                "환불 전 리뷰"
        ));

        paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);

        assertThat(reviewRepository.existsByReservationId(reservationId)).isFalse();
        assertThat(reservationRepository.findById(reservationId).orElseThrow()
                .getReviewedAt()).isNull();
    }

    @Test
    @DisplayName("리뷰 작성과 환불이 경합해도 REFUNDED 결제에는 리뷰와 작성 이력이 남지 않는다")
    void reviewCreateAndRefund_concurrently_preservesRefundInvariant()
            throws InterruptedException {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        Long reservationId = paymentRepository.findById(paymentId).orElseThrow()
                .getReservationId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                reviewApplicationService.create(
                        GUARDIAN_ID,
                        reservationId,
                        new ReviewRequest(new BigDecimal("4.5"), "경합 리뷰")
                );
            } catch (ServiceException e) {
                if (e.getErrorCode() != ReviewErrorCode.PAYMENT_NOT_COMPLETED) {
                    unexpected.add(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unexpected.add(e);
            } finally {
                doneLatch.countDown();
            }
        });
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);
            } catch (Throwable throwable) {
                unexpected.add(throwable);
            } finally {
                doneLatch.countDown();
            }
        });

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpected).isEmpty();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reviewRepository.existsByReservationId(reservationId)).isFalse();
        assertThat(reservationRepository.findById(reservationId).orElseThrow()
                .getReviewedAt()).isNull();
    }

    @Test
    @DisplayName("리뷰 작성권 초기화가 실패하면 환불 이력과 결제 전이를 함께 롤백한다")
    void reviewResetFailure_rollsBackRefundCompletion() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        Long reservationId = paymentRepository.findById(paymentId).orElseThrow()
                .getReservationId();
        RefundClaim claim = paymentRefundTxService.claim(
                paymentId,
                STAFF_MEMBER_ID,
                REASON
        );
        reservationRepository.deleteById(reservationId);

        assertThatThrownBy(() -> paymentRefundTxService.complete(
                claim.refundId(),
                paymentId,
                claim.claimToken(),
                "PG-CLEANUP-FAIL"
        )).isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.NOT_FOUND);

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        PaymentRefund refund = paymentRefundRepository.findByPaymentId(paymentId)
                .orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getPgCancelId()).isNull();
    }

    @Test
    @DisplayName("반복 환불 요청은 멱등하다 — PG를 다시 호출하지 않고 알림도 재발행하지 않는다")
    void repeat_isIdempotent() {
        Long paymentId = persistPayment(PaymentStatus.PAID);

        paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);
        PaymentHistoryResponse second = paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);

        assertThat(second.status()).isEqualTo(PaymentStatus.REFUNDED);
        // 두 번째 요청은 선점 전에 REFUNDED로 걸러지므로 PG 취소가 한 번만 일어난다(이중 환불 금지).
        assertThat(fakePaymentGateway.cancelCallCount()).isEqualTo(1);
        verify(notificationPublisher, times(1)).publishChargeResult(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("PG 취소가 실패하면 결제는 PAID로 남고 이력은 FAILED로 기록되며 502를 반환한다")
    void gatewayFailure_keepsPaidAndRecordsFailure() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        fakePaymentGateway.stubCancelFailure(
                GatewayFailureReason.NON_RETRIABLE, "CANCELLABLE_AMOUNT_CONSUMED", "취소 불가");

        assertThatThrownBy(() -> paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_GATEWAY_FAILED);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getRefundedAt()).isNull();

        PaymentRefund refund = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo(GatewayFailureReason.NON_RETRIABLE.name());
        // 환불이 성립하지 않았으므로 보호자에게 알리지 않는다.
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("실패한 환불의 재시도는 같은 멱등키를 재사용해 성공하고 처리 스태프가 갱신된다")
    void retryAfterFailure_reusesSameIdempotencyKey() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        fakePaymentGateway.stubCancelFailure(GatewayFailureReason.RETRIABLE, "PG_TIMEOUT", "일시 장애");
        assertThatThrownBy(() -> paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON))
                .isInstanceOf(ServiceException.class);
        String firstKey = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow().getMerchantRefundId();

        // 장애 해소 후 다른 스태프가 재시도한다.
        fakePaymentGateway.clearCancelFailure();
        given(staffHospitalPort.findHospitalIdByMemberId(OTHER_STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        PaymentHistoryResponse response =
                paymentRefundService.refund(paymentId, OTHER_STAFF_MEMBER_ID, "재시도");

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        PaymentRefund refund = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        // 새 키를 만들면 PG가 이중 취소를 막을 수 없다 — 재시도는 반드시 같은 키를 재사용해야 한다.
        assertThat(refund.getMerchantRefundId()).isEqualTo(firstKey);
        assertThat(fakePaymentGateway.receivedMerchantRefundIds()).containsOnly(firstKey);
        // 실제로 환불을 완료시킨 스태프·사유가 감사 기록에 남는다.
        assertThat(refund.getRefundedBy()).isEqualTo(OTHER_STAFF_MEMBER_ID);
        assertThat(refund.getReason()).isEqualTo("재시도");
        assertThat(refund.getFailureReason()).isNull();
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), anyLong(), eq(paymentId), eq(PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("환불 진행 중(신선한 선점)에 들어온 요청은 409(REFUND_IN_PROGRESS)로 거부되고 PG를 호출하지 않는다")
    void freshClaim_rejectsConcurrentRequest() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        // PG 취소 직전에 멈춘 상태를 재현한다 — 선점만 되어 있고 결제는 PAID.
        // claimedAt은 반드시 서울 기준 Clock으로 만든다. LocalDateTime.now()를 쓰면 JVM 기본 시간대가 UTC인
        // 환경(CI)에서 방금 만든 선점이 9시간 낡은 것으로 보여 "진행 중"이 아니라 회수 대상이 된다.
        paymentRefundRepository.saveAndFlush(PaymentRefund.requested(
                paymentId, "rfd_inflight_" + paymentId, AMOUNT, REASON, STAFF_MEMBER_ID,
                LocalDateTime.now(clock), "tok_inflight_" + paymentId));

        assertThatThrownBy(() -> paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_IN_PROGRESS);

        // 진행 중인 선점을 가로채지 않았으므로 PG 취소는 일어나지 않는다.
        assertThat(fakePaymentGateway.cancelCallCount()).isZero();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("임계를 넘겨 멈춘 선점은 회수해 같은 멱등키로 재시도하고 환불을 확정한다")
    void staleClaim_isRecovered() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        String stuckKey = "rfd_stuck_" + paymentId;
        // claim-stale-after-ms(기본 2분)보다 오래된 선점 — 앱이 PG 취소 도중 죽어 남은 행을 재현한다.
        paymentRefundRepository.saveAndFlush(PaymentRefund.requested(
                paymentId, stuckKey, AMOUNT, REASON, STAFF_MEMBER_ID,
                LocalDateTime.now(clock).minusMinutes(10), "tok_stuck_" + paymentId));

        PaymentHistoryResponse response = paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, "멈춘 환불 복구");

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        PaymentRefund refund = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        // 멈춘 선점의 키를 그대로 써야 PG가 기존 취소 결과를 돌려준다(이중 취소 금지).
        assertThat(refund.getMerchantRefundId()).isEqualTo(stuckKey);
        assertThat(fakePaymentGateway.receivedMerchantRefundIds()).containsOnly(stuckKey);
    }

    @Test
    @DisplayName("선점이 회수된 뒤 도착한 이전 소유자의 실패·확정은 반영되지 않는다(소유권 펜스)")
    void stolenClaim_lateResultsAreFenced() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        String stuckKey = "rfd_fence_" + paymentId;
        String oldToken = "tok_old_" + paymentId;
        // 이전 소유자(A)의 멈춘 선점.
        PaymentRefund stuck = paymentRefundRepository.saveAndFlush(PaymentRefund.requested(
                paymentId, stuckKey, AMOUNT, REASON, STAFF_MEMBER_ID,
                LocalDateTime.now(clock).minusMinutes(10), oldToken));

        // 새 소유자(B)가 선점을 회수해 환불을 확정한다.
        paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, "회수 후 재시도");
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);

        // A가 뒤늦게 실패를 기록하려 해도 펜스가 어긋나 갱신되지 않는다 — 없으면 이력이 FAILED로 뒤집힌다.
        paymentRefundTxService.fail(stuck.getId(), oldToken, "LATE_FAILURE");
        PaymentRefund afterLateFail = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(afterLateFail.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(afterLateFail.getFailureReason()).isNull();

        // A가 뒤늦게 성공을 확정하려 해도 결제 상태를 다시 건드리지 않는다(알림 재발행 없음).
        RefundOutcome lateComplete =
                paymentRefundTxService.complete(stuck.getId(), paymentId, oldToken, "PG-LATE");
        assertThat(lateComplete.applied()).isFalse();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), anyLong(), eq(paymentId), eq(PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("결제를 REFUNDED로 확정하지 못하면 이력 확정까지 롤백해 재시도로 복구할 수 있게 남긴다")
    void paymentTransitionFails_rollsBackHistorySoRetryCanRecover() {
        // 정상 경로에서는 도달하지 않는 방어 분기다(claim이 PAID만 통과시키고 펜스가 경합을 막는다).
        // 다만 도달하면 "이력 COMPLETED + 결제 미환불"이 남고, 이후 재요청이 reclaim()의 COMPLETED 분기에
        // 막혀 스스로 복구되지 않는다. 그래서 커밋하지 않고 롤백하는지 Tx 경계를 직접 호출해 고정한다(리뷰 P1).
        Long paymentId = persistPayment(PaymentStatus.PENDING);   // PAID가 아니므로 markRefundedIfPaid가 0건이 된다
        String token = "tok_conflict_" + paymentId;
        PaymentRefund requested = paymentRefundRepository.saveAndFlush(PaymentRefund.requested(
                paymentId, "rfd_conflict_" + paymentId, AMOUNT, REASON, STAFF_MEMBER_ID,
                LocalDateTime.now(clock), token));

        assertThatThrownBy(() -> paymentRefundTxService.complete(
                requested.getId(), paymentId, token, "PG-CONFLICT"))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_STATE_CONFLICT);

        // 이력이 COMPLETED로 커밋되지 않고 REQUESTED로 남아야 한다 — 같은 멱등키 재시도로 복구할 수 있는 상태다.
        PaymentRefund afterRollback = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(afterRollback.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(afterRollback.getPgCancelId()).isNull();
        assertThat(afterRollback.getRefundedAt()).isNull();
        // 결제도 그대로다.
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("PG가 요청과 다른 금액을 취소하면 환불을 확정하지 않고 PAID를 유지한다(이력은 FAILED)")
    void amountMismatch_doesNotConfirmRefund() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        String key = "rfd_mismatch_" + paymentId;
        paymentRefundRepository.saveAndFlush(PaymentRefund.requested(
                paymentId, key, AMOUNT, REASON, STAFF_MEMBER_ID,
                LocalDateTime.now(clock).minusMinutes(10), "tok_mismatch_" + paymentId));
        // 같은 멱등키에 요청 금액과 다른 취소 결과를 심어 둔다.
        fakePaymentGateway.stubCancelAmount(key, AMOUNT - 1_000);

        assertThatThrownBy(() -> paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_GATEWAY_FAILED);

        // 실제와 다른 금액을 REFUNDED로 확정하면 이력이 거짓이 된다 — 확정하지 않고 운영 확인 대상으로 남긴다.
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
        PaymentRefund refund = paymentRefundRepository.findByPaymentId(paymentId).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo("REFUND_AMOUNT_MISMATCH");
        verify(notificationPublisher, never()).publishChargeResult(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("PENDING·OFFLINE_REQUIRED·OFFLINE_PAID 환불은 409(REFUND_PRECONDITION_FAILED)로 거부한다")
    void nonRefundableStatuses_rejected() {
        // 현장 현금 수납(OFFLINE_PAID)은 반환 절차·증빙 정책이 미정이라 이번 범위 밖이다(PRD §환불).
        for (PaymentStatus status : List.of(
                PaymentStatus.PENDING, PaymentStatus.OFFLINE_REQUIRED, PaymentStatus.OFFLINE_PAID)) {
            Long paymentId = persistPayment(status);

            assertThatThrownBy(() -> paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON))
                    .as("status=%s", status)
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_PRECONDITION_FAILED);

            assertThat(paymentRefundRepository.findByPaymentId(paymentId)).isEmpty();
        }
        assertThat(fakePaymentGateway.cancelCallCount()).isZero();
    }

    @Test
    @DisplayName("타병원 스태프의 환불은 403(FORBIDDEN_HOSPITAL)이며 PG를 호출하지 않는다")
    void otherHospitalStaff_forbidden() {
        Long paymentId = persistPayment(PaymentStatus.PAID);

        assertThatThrownBy(() -> paymentRefundService.refund(paymentId, OTHER_STAFF_MEMBER_ID, REASON))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);

        assertThat(fakePaymentGateway.cancelCallCount()).isZero();
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("알림 발행이 실패해도 환불 확정은 되돌아가지 않는다")
    void notificationFailure_doesNotRollbackRefund() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        org.mockito.BDDMockito.willThrow(new RuntimeException("알림 저장 실패"))
                .given(notificationPublisher)
                .publishChargeResult(anyLong(), anyLong(), anyLong(), any());

        PaymentHistoryResponse response = paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentRefundRepository.findByPaymentId(paymentId).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("같은 결제에 동시 환불이 몰려도 PG 취소는 1회·이력 1건·상태 전이 1회만 성립한다")
    void concurrentRefund_onlyOneRefunds() throws InterruptedException {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        List<PaymentStatus> succeeded = new CopyOnWriteArrayList<>();
        List<ServiceException> rejected = new CopyOnWriteArrayList<>();
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
                    succeeded.add(paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON).status());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ServiceException e) {
                    rejected.add(e);
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // 도메인 예외(409) 외의 예외는 없어야 한다 — UNIQUE 위반이 그대로 새어 500이 되면 실패로 본다.
        assertThat(unexpected).isEmpty();
        // 성공 응답은 모두 REFUNDED다(선점 승자 + 이후 멱등 응답). 선점에서 진 요청은 409 REFUND_IN_PROGRESS다.
        assertThat(succeeded).isNotEmpty().containsOnly(PaymentStatus.REFUNDED);
        assertThat(rejected).allSatisfy(e ->
                assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.REFUND_IN_PROGRESS));
        assertThat(succeeded.size() + rejected.size()).isEqualTo(CONCURRENT_REQUESTS);

        // 핵심 불변식 — PG 취소는 정확히 1회, 환불 이력은 1건, 알림은 1회(= 상태 전이 1회).
        assertThat(fakePaymentGateway.cancelCallCount()).isEqualTo(1);
        assertThat(paymentRefundRepository.findAll().stream()
                .filter(r -> r.getPaymentId().equals(paymentId)).toList()).hasSize(1);
        verify(notificationPublisher, times(1))
                .publishChargeResult(eq(GUARDIAN_ID), anyLong(), eq(paymentId), eq(PaymentStatus.REFUNDED));
        Payment refunded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("환불된 결제에 PG 취소 웹훅이 들어와도 상태가 REFUNDED에서 바뀌지 않는다")
    void cancelledWebhook_doesNotOverwriteRefunded() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);
        String merchantPaymentId = paymentRepository.findById(paymentId).orElseThrow().getMerchantPaymentId();

        // 우리가 호출한 취소 때문에 PortOne이 보내는 이벤트다. 웹훅은 트리거일 뿐 상태는 재조회로 확정되는데,
        // 확정 경로의 조건부 UPDATE가 모두 WHERE status='PENDING'이라 REFUNDED를 덮어쓰지 못해야 한다.
        String webhookId = "wh_refund_" + paymentId;
        createdWebhookIds.add(webhookId);
        paymentWebhookService.handle(webhookId, "Transaction.Cancelled", merchantPaymentId);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isNotNull();
        // 알림도 환불 1회로 유지된다 — 웹훅이 상태를 재확정했다면 여기서 2회가 된다.
        verify(notificationPublisher, times(1)).publishChargeResult(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("환불한 예약은 다시 청구할 수 없다(정정 재청구는 확장) — DUPLICATE_CHARGE로 막힌다")
    void refunded_reservationCannotBeRechargedYet() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        Long reservationId = paymentRepository.findById(paymentId).orElseThrow().getReservationId();
        paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);

        // preRecord는 상태와 무관하게 existsByReservationId로 이중 청구를 막으므로, REFUNDED 행이 남아 있는
        // 예약은 금액을 고쳐 다시 청구할 수 없다. UNIQUE(reservation_id)가 예약당 결제 1건을 강제하기 때문에
        // 정정 재청구는 스키마 확장(payments 1:N) 없이는 불가능하다 — PRD·SA가 확장으로 분류한 그 항목이다.
        // 의도된 한계를 테스트로 고정해, 나중에 조용히 동작이 바뀌거나 놓친 요구사항으로 오해되지 않게 한다.
        assertThatThrownBy(() -> paymentChargeService.preRecord(reservationId, STAFF_MEMBER_ID, AMOUNT - 10_000))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.DUPLICATE_CHARGE);
    }

    @Test
    @DisplayName("환불된 결제는 정산 배치 대상 조회에 포함되지 않는다")
    void refunded_isNotReconcileTarget() {
        Long paymentId = persistPayment(PaymentStatus.PAID);
        paymentRefundService.refund(paymentId, STAFF_MEMBER_ID, REASON);

        // 정산 배치는 PENDING만 수렴 대상으로 삼는다 — REFUNDED가 섞이면 재조회 결과에 따라 상태가 되돌아갈 수 있다.
        List<Payment> targets = paymentRepository.findReconcileTargets(
                PaymentStatus.PENDING, LocalDateTime.now().plusDays(1), PageRequest.of(0, 100));

        assertThat(targets).extracting(Payment::getId).doesNotContain(paymentId);
    }

    /**
     * 지정 상태의 결제를 저장하고 예약 port를 스텁한다. 상태는 도메인 전이 메서드로 만들어 상태 머신 밖의
     * 조합(예: PAID인데 채널 null)이 픽스처로 새지 않게 한다. OFFLINE_PAID만 저장 후 조건부 UPDATE로 전이한다.
     */
    private Long persistPayment(PaymentStatus status) {
        LocalDateTime now = LocalDateTime.now(clock);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.request(
                GUARDIAN_ID,
                System.nanoTime(),
                HOSPITAL_ID,
                System.nanoTime(),
                7L,
                "초코",
                "DOG",
                now,
                now.plusDays(1)
        ));
        long reservationId = reservation.getId();
        reservationIds.add(reservationId);
        Payment payment = Payment.pending(reservationId, "pay_" + reservationId, 7L, "VISA", "1234", AMOUNT);
        switch (status) {
            // PENDING은 선기록 상태 그대로 쓴다. REFUNDED는 이 헬퍼로 만들지 않는다(환불 경로로만 도달).
            case PENDING -> { }
            case PAID -> payment.markPaid("PG-" + reservationId, now);
            case OFFLINE_REQUIRED, OFFLINE_PAID -> payment.markOfflineRequired("NON_RETRIABLE", 0);
            case REFUNDED -> throw new IllegalArgumentException("REFUNDED 픽스처는 환불 경로로만 만든다");
        }
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        paymentIds.add(paymentId);
        // 예약 port는 아래 정산 호출보다 먼저 스텁돼야 한다.
        given(reservationLookupPort.findForCharge(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, 7L, true)));
        if (status == PaymentStatus.OFFLINE_PAID) {
            // 조건부 UPDATE는 트랜잭션 안에서만 동작하므로 실제 정산 경로(#36)로 전이시킨다.
            paymentOfflineSettleTxService.settle(paymentId, STAFF_MEMBER_ID);
        }
        return paymentId;
    }
}
