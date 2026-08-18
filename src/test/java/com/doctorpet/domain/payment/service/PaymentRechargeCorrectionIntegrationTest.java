package com.doctorpet.domain.payment.service;

import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.draftToken;
import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.persistDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Level 3 — 결제 실패 셀프 복구(3.3)·정정 재청구(3.5-a) STRICT 통합·동시성 검증(SA §5-2·§9-4). 실 MySQL에서
 * 활성 결제 UNIQUE·조건부 대체(supersede)·이중 수납 방지를 확인한다. 실제 배선(예약·스태프 어댑터)과 fake 게이트웨이를
 * 그대로 태운다. 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED(환경 문제이지 회귀 아님).
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>정정 재청구는 REFUNDED 활성 결제를 대체하고 새 활성 결제를 만든다(correction_of 체인, 활성 1건 유지).</li>
 *   <li>셀프 복구는 OFFLINE_REQUIRED 활성 결제를 대체하고 원 항목·총액을 승계한다(recovery_of 체인).</li>
 *   <li>셀프 복구 vs 오프라인 정산은 같은 원 행의 조건부 UPDATE로 <b>정확히 하나만</b> 성립한다 — 자동결제와 현장
 *       수납이 동시에 성립하지 않는다(이중 수납 방지).</li>
 *   <li>동시 정정 재청구는 정확히 하나만 성립한다.</li>
 * </ul>
 */
@SpringBootTest
class PaymentRechargeCorrectionIntegrationTest {

    private static final Long HOSPITAL_ID = 5252L;
    private static final int AMOUNT = 50_000;
    private static final int CONCURRENT_REQUESTS = 16;

    @Autowired private PaymentApplicationService paymentApplicationService;
    @Autowired private PaymentOfflineSettlementService paymentOfflineSettlementService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentItemRepository paymentItemRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private Long staffMemberId;
    private Long guardianMemberId;
    private Long paymentMethodId;
    private final List<Long> reservationIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // @Modifying 리포지토리 메서드(markRefundedIfPaid·stampDraftsToPayment)는 flushAutomatically라 활성
        // 트랜잭션이 필요하다. 운영 코드는 서비스 트랜잭션 안에서 호출하지만, 픽스처는 직접 호출하므로 여기서 감싼다.
        tx = new TransactionTemplate(transactionManager);
        long nano = System.nanoTime();
        Member staff = Member.createGuardian("staff-" + nano + "@example.com", "pw", "스태프");
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", HOSPITAL_ID);
        staffMemberId = memberRepository.saveAndFlush(staff).getId();

        guardianMemberId = memberRepository.saveAndFlush(
                Member.createGuardian("guardian-" + nano + "@example.com", "pw", "보호자")).getId();

        paymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(guardianMemberId, billingKeyCryptor.encrypt("billing-key"), "VISA", "1234")).getId();
    }

    @AfterEach
    void tearDown() {
        for (Long reservationId : reservationIds) {
            for (Payment payment : paymentRepository.findByReservationIdOrderByIdDesc(reservationId)) {
                paymentItemRepository.deleteAll(paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId()));
                paymentRepository.deleteById(payment.getId());
            }
            paymentItemRepository.deleteAll(
                    paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId));
            reservationRepository.deleteById(reservationId);
        }
        paymentMethodRepository.deleteById(paymentMethodId);
        memberRepository.deleteById(staffMemberId);
        memberRepository.deleteById(guardianMemberId);
    }

    @Test
    @DisplayName("정정 재청구는 REFUNDED 활성 결제를 대체하고 correction_of로 이어진 새 활성 PAID를 만든다(활성 1건)")
    void correction_supersedesRefundedAndCreatesNewActivePayment() {
        Long reservationId = persistReservation();
        Long refundedId = chargeToRefunded(reservationId);

        // 스태프가 정정 초안을 새로 작성한다(원 50,000 → 30,000 정정).
        persistDraft(paymentItemRepository, reservationId, 30_000);
        PaymentChargeResponse corrected = paymentApplicationService.correctionCharge(
                reservationId, staffMemberId, draftToken(paymentItemRepository, reservationId));

        assertThat(corrected.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(corrected.amount()).isEqualTo(30_000);
        assertThat(corrected.paymentId()).isNotEqualTo(refundedId);

        Payment refunded = paymentRepository.findById(refundedId).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.isActive()).isFalse();

        Payment fresh = paymentRepository.findById(corrected.paymentId()).orElseThrow();
        assertThat(fresh.isActive()).isTrue();
        assertThat(fresh.getCorrectionOf()).isEqualTo(refundedId);
        assertThat(fresh.getRecoveryOf()).isNull();
        assertThat(activeCount(reservationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("셀프 복구는 OFFLINE_REQUIRED 활성 결제를 대체하고 원 항목·총액을 승계한 새 활성 PAID를 만든다")
    void recovery_supersedesOfflineRequiredAndCopiesItems() {
        Long reservationId = persistReservation();
        Long offlineRequiredId = persistOfflineRequiredWithItem(reservationId);

        PaymentChargeResponse recovered = paymentApplicationService.recharge(
                reservationId, guardianMemberId, paymentMethodId);

        assertThat(recovered.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(recovered.amount()).isEqualTo(AMOUNT);
        assertThat(recovered.paymentId()).isNotEqualTo(offlineRequiredId);

        Payment origin = paymentRepository.findById(offlineRequiredId).orElseThrow();
        assertThat(origin.getStatus()).isEqualTo(PaymentStatus.OFFLINE_REQUIRED);
        assertThat(origin.isActive()).isFalse();

        Payment fresh = paymentRepository.findById(recovered.paymentId()).orElseThrow();
        assertThat(fresh.isActive()).isTrue();
        assertThat(fresh.getRecoveryOf()).isEqualTo(offlineRequiredId);
        assertThat(fresh.getCorrectionOf()).isNull();
        // 원 항목이 새 복구 결제로 복제됐다.
        assertThat(paymentItemRepository.findByPaymentIdOrderByIdAsc(fresh.getId())).hasSize(1);
        assertThat(activeCount(reservationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("항목화 이전(항목 없는) 레거시 OFFLINE_REQUIRED의 셀프 복구는 가짜 항목 없이 원 총액만 승계한다")
    void recovery_legacyItemlessPayment_inheritsTotalOnly() {
        Long reservationId = persistReservation();
        Long offlineRequiredId = persistOfflineRequiredWithoutItem(reservationId);

        PaymentChargeResponse recovered = paymentApplicationService.recharge(
                reservationId, guardianMemberId, paymentMethodId);

        assertThat(recovered.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(recovered.amount()).isEqualTo(AMOUNT);

        Payment fresh = paymentRepository.findById(recovered.paymentId()).orElseThrow();
        assertThat(fresh.getRecoveryOf()).isEqualTo(offlineRequiredId);
        assertThat(fresh.isActive()).isTrue();
        // 원 결제에 항목이 없었으므로 새 복구 결제도 항목을 만들지 않는다(영수증 빈 배열 규칙).
        assertThat(paymentItemRepository.findByPaymentIdOrderByIdAsc(fresh.getId())).isEmpty();
        assertThat(activeCount(reservationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("셀프 복구와 오프라인 정산이 동시에 몰려도 정확히 하나만 성립한다 — 자동결제·현장 수납 이중 수납 없음")
    void concurrentRechargeVsOfflineSettle_onlyOneWins() throws InterruptedException {
        Long reservationId = persistReservation();
        Long offlineRequiredId = persistOfflineRequiredWithItem(reservationId);

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            boolean recharge = i % 2 == 0;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (recharge) {
                        paymentApplicationService.recharge(reservationId, guardianMemberId, paymentMethodId);
                    } else {
                        paymentOfflineSettlementService.settle(offlineRequiredId, staffMemberId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException loserOrWinner) {
                    // 경합에서 진 요청은 예외를 받는다(PAYMENT_ALREADY_SUPERSEDED·OFFLINE_PRECONDITION_FAILED·
                    // RECHARGE_PRECONDITION_FAILED). 그 자체는 정상이므로 아래 최종 상태로 "정확히 하나만 성립"을 검증한다.
                    failures.add(loserOrWinner);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        List<Payment> all = paymentRepository.findByReservationIdOrderByIdDesc(reservationId);
        long active = all.stream().filter(Payment::isActive).count();
        assertThat(active).isEqualTo(1);
        // 실제 "수납"이 성립한 경로: 셀프 복구가 이겼으면 새 PAID(recovery_of), 오프라인 정산이 이겼으면 원 OFFLINE_PAID.
        long settlements = all.stream()
                .filter(p -> p.getStatus() == PaymentStatus.OFFLINE_PAID
                        || (p.getStatus() == PaymentStatus.PAID && p.getRecoveryOf() != null))
                .count();
        assertThat(settlements).isEqualTo(1);
        // 절대 금지: 원 결제 OFFLINE_PAID와 새 PAID 복구가 동시에 존재(이중 수납).
        boolean originOfflinePaid = all.stream().anyMatch(p -> p.getStatus() == PaymentStatus.OFFLINE_PAID);
        boolean paidRecovery = all.stream()
                .anyMatch(p -> p.getStatus() == PaymentStatus.PAID && p.getRecoveryOf() != null);
        assertThat(originOfflinePaid && paidRecovery).isFalse();
    }

    @Test
    @DisplayName("같은 예약에 동시 정정 재청구가 몰려도 정확히 하나만 성립하고 나머지는 거부된다")
    void concurrentCorrection_onlyOneWins() throws InterruptedException {
        Long reservationId = persistReservation();
        chargeToRefunded(reservationId);
        persistDraft(paymentItemRepository, reservationId, 30_000);
        String token = draftToken(paymentItemRepository, reservationId);

        List<PaymentStatus> successes = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    successes.add(paymentApplicationService
                            .correctionCharge(reservationId, staffMemberId, token).status());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException loser) {
                    failures.add(loser);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 정확히 하나만 성립한다.
        assertThat(successes).hasSize(1);
        assertThat(activeCount(reservationId)).isEqualTo(1);
        Payment active = paymentRepository.findByReservationIdOrderByIdDesc(reservationId).stream()
                .filter(Payment::isActive)
                .findFirst()
                .orElseThrow();
        assertThat(active.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(active.getCorrectionOf()).isNotNull();
    }

    @Test
    @DisplayName("전액 환불된(REFUNDED) 활성 결제가 있는 예약에 정상 청구는 DUPLICATE_CHARGE로 거부된다(정정 경로만 허용)")
    void normalCharge_rejectedWhenRefundedActiveExists() {
        Long reservationId = persistReservation();
        chargeToRefunded(reservationId);
        persistDraft(paymentItemRepository, reservationId, 20_000);

        assertThatThrownBy(() -> paymentApplicationService.charge(
                reservationId, staffMemberId, draftToken(paymentItemRepository, reservationId)))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.DUPLICATE_CHARGE);
    }

    // --- 픽스처 ---

    /** 진료 완료 예약을 저장하고(초안 AMOUNT 포함) id를 반환한다. */
    private Long persistReservation() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = Reservation.request(
                guardianMemberId, 1L, HOSPITAL_ID, System.nanoTime(), paymentMethodId, "나비", "CAT", now);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.TREATMENT_COMPLETED);
        ReflectionTestUtils.setField(reservation, "confirmedAt", now);
        Long reservationId = reservationRepository.saveAndFlush(reservation).getId();
        reservationIds.add(reservationId);
        persistDraft(paymentItemRepository, reservationId, AMOUNT);
        return reservationId;
    }

    /** 정상 청구로 PAID를 만든 뒤 전액 환불해 REFUNDED 활성 결제를 만들고 그 id를 반환한다. */
    private Long chargeToRefunded(Long reservationId) {
        PaymentChargeResponse paid = paymentApplicationService.charge(
                reservationId, staffMemberId, draftToken(paymentItemRepository, reservationId));
        assertThat(paid.status()).isEqualTo(PaymentStatus.PAID);
        int refundApplied = tx.execute(status ->
                paymentRepository.markRefundedIfPaid(paid.paymentId(), LocalDateTime.now()));
        assertThat(refundApplied).isEqualTo(1);
        return paid.paymentId();
    }

    /** 초안 항목(AMOUNT)이 스탬프된 OFFLINE_REQUIRED 활성 결제를 만들고 그 id를 반환한다. */
    private Long persistOfflineRequiredWithItem(Long reservationId) {
        Payment payment = Payment.pending(
                reservationId, "pay_" + System.nanoTime(), paymentMethodId, "VISA", "1234", AMOUNT);
        payment.markOfflineRequired("NON_RETRIABLE", 0);
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        // persistReservation이 깐 초안(AMOUNT)을 이 OFFLINE_REQUIRED 결제로 스탬프해 항목 있는 원 결제를 만든다.
        int stamped = tx.execute(status -> paymentItemRepository.stampDraftsToPayment(reservationId, paymentId));
        assertThat(stamped).isEqualTo(1);
        return paymentId;
    }

    /** 항목이 없는(항목화 이전 레거시) OFFLINE_REQUIRED 활성 결제를 만들고 그 id를 반환한다. */
    private Long persistOfflineRequiredWithoutItem(Long reservationId) {
        Payment payment = Payment.pending(
                reservationId, "pay_" + System.nanoTime(), paymentMethodId, "VISA", "1234", AMOUNT);
        payment.markOfflineRequired("NON_RETRIABLE", 0);
        Long paymentId = paymentRepository.saveAndFlush(payment).getId();
        // persistReservation이 깐 초안을 이 결제로 스탬프하지 않고 제거해, 항목 없는 레거시 결제를 재현한다.
        tx.execute(status -> paymentItemRepository.deleteDraftsByReservationId(reservationId));
        return paymentId;
    }

    private long activeCount(Long reservationId) {
        return paymentRepository.findByReservationIdOrderByIdDesc(reservationId).stream()
                .filter(Payment::isActive)
                .count();
    }
}
