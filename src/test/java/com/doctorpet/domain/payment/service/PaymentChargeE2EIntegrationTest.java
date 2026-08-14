package com.doctorpet.domain.payment.service;

import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.deleteItems;
import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.persistDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.support.PaymentItemTestSupport;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.dto.request.ReservationPaymentMethodUpdateRequest;
import com.doctorpet.domain.reservation.adapter.ReservationChargeLookupAdapter;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.service.ReservationApplicationService;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Level 3 — 진료비 청구 E2E 통합 검증. 목 없이 실제 배선을 그대로 탄다:
 * 스태프 소속 병원 해석(StaffHospitalPort → MemberStaffHospitalAdapter → MemberService)과
 * 예약 조회(ReservationLookupPort → ReservationChargeLookupAdapter → ReservationRepository)를
 * 실제 빈으로 연결하고, 실제 MySQL에 저장된 예약·회원·결제수단으로 청구가 성립하는지 확인한다.
 * 게이트웨이는 fake다. 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class PaymentChargeE2EIntegrationTest {

    private static final Long HOSPITAL_ID = 4242L;
    private static final int AMOUNT = 50_000;

    @Autowired private PaymentApplicationService paymentApplicationService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentItemRepository paymentItemRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;
    @Autowired private ReservationApplicationService reservationApplicationService;
    @MockitoSpyBean private ReservationChargeLookupAdapter reservationChargeLookupAdapter;
    @MockitoSpyBean private ReservationService reservationService;

    private Long staffMemberId;
    private Long guardianMemberId;
    private Long paymentMethodId;
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> additionalPaymentMethodIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        // 스태프는 전용 팩토리가 없어(시드로 생성되는 역할) 테스트에서 role·hospitalId를 직접 세팅한다.
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
            Long paymentId = paymentRepository.findByReservationId(reservationId)
                    .map(payment -> {
                        Long id = payment.getId();
                        paymentRepository.delete(payment);
                        return id;
                    })
                    .orElse(null);
            deleteItems(paymentItemRepository, reservationId, paymentId);
            reservationRepository.deleteById(reservationId);
        }
        paymentMethodRepository.deleteById(paymentMethodId);
        additionalPaymentMethodIds.forEach(paymentMethodRepository::deleteById);
        memberRepository.deleteById(staffMemberId);
        memberRepository.deleteById(guardianMemberId);
    }

    @Test
    @DisplayName("진료 완료된 예약을 자병원 스태프가 청구하면 실제 배선으로 PAID가 확정된다")
    void chargeCompletedReservation_paid() {
        Long reservationId = persistReservation(true);

        PaymentChargeResponse response =
                paymentApplicationService.charge(reservationId, staffMemberId, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(response.cardLast4Snapshot()).isEqualTo("1234");

        Payment saved = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(saved.getPgPaymentId()).isNotNull();
        assertThat(saved.getPaidAt()).isNotNull();
    }

    @Test
    @DisplayName("진료 완료 전(CONFIRMED) 예약 청구는 실제 예약 상태를 읽어 RESERVATION_NOT_CHARGEABLE로 거부된다")
    void chargeNotCompletedReservation_rejected() {
        Long reservationId = persistReservation(false);

        assertThatThrownBy(() ->
                paymentApplicationService.charge(reservationId, staffMemberId, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId)))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);

        assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("진료 전 재지정한 결제수단으로 최초 청구하고 카드 스냅샷을 남긴다")
    void changedReservationPaymentMethod_isUsedForFirstCharge() {
        Long reservationId = persistReservation(false);
        Long replacementPaymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(
                        guardianMemberId,
                        billingKeyCryptor.encrypt("replacement-billing-key"),
                        "MASTER",
                        "5678"
                )).getId();
        additionalPaymentMethodIds.add(replacementPaymentMethodId);

        reservationApplicationService.changePaymentMethod(
                guardianMemberId,
                reservationId,
                new ReservationPaymentMethodUpdateRequest(replacementPaymentMethodId)
        );
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reservation.getPaymentMethodId()).isEqualTo(replacementPaymentMethodId);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.TREATMENT_COMPLETED);
        reservationRepository.saveAndFlush(reservation);
        // 진료 완료로 전환된 시점에 초안 항목을 깐다(persistReservation(false)에는 초안이 없다).
        persistDraft(paymentItemRepository, reservationId, AMOUNT);

        paymentApplicationService.charge(reservationId, staffMemberId, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));

        Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(payment.getPaymentMethodId()).isEqualTo(replacementPaymentMethodId);
        assertThat(payment.getCardBrandSnapshot()).isEqualTo("MASTER");
        assertThat(payment.getCardLast4Snapshot()).isEqualTo("5678");
    }

    @Test
    @DisplayName("청구가 예약 행 잠금과 결제 선기록을 먼저 커밋하면 결제수단 재지정은 PAYMENT_ALREADY_STARTED으로 거부된다")
    void chargeFirst_blocksReassignmentAndKeepsOriginalPaymentSnapshot() throws Exception {
        Long reservationId = persistReservation(true);
        Long replacementPaymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(
                        guardianMemberId,
                        billingKeyCryptor.encrypt("replacement-billing-key"),
                        "MASTER",
                        "5678"
                )).getId();
        additionalPaymentMethodIds.add(replacementPaymentMethodId);
        CountDownLatch chargeLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseCharge = new CountDownLatch(1);
        CountDownLatch reassignmentLockAttempted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            Object reservation = invocation.callRealMethod();
            chargeLockAcquired.countDown();
            assertThat(releaseCharge.await(5, TimeUnit.SECONDS)).isTrue();
            return reservation;
        }).when(reservationChargeLookupAdapter).findForChargeForUpdate(eq(reservationId));
        doAnswer(invocation -> {
            reassignmentLockAttempted.countDown();
            return invocation.callRealMethod();
        }).when(reservationService).findMyReservationForUpdate(eq(guardianMemberId), eq(reservationId));

        try {
            Future<PaymentChargeResponse> charge = executor.submit(() ->
                    paymentApplicationService.charge(reservationId, staffMemberId, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId)));
            assertThat(chargeLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> reassignment = executor.submit(() -> reservationApplicationService.changePaymentMethod(
                    guardianMemberId,
                    reservationId,
                    new ReservationPaymentMethodUpdateRequest(replacementPaymentMethodId)
            ));
            assertThat(reassignmentLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();

            releaseCharge.countDown();
            assertThat(charge.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.PAID);
            Throwable failure = catchThrowable(() -> reassignment.get(10, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(failure.getCause()).isInstanceOf(ServiceException.class);
            assertThat(((ServiceException) failure.getCause()).getErrorCode())
                    .isEqualTo(ReservationErrorCode.PAYMENT_ALREADY_STARTED);
        } finally {
            releaseCharge.countDown();
            executor.shutdownNow();
        }

        Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(payment.getPaymentMethodId()).isEqualTo(paymentMethodId);
        assertThat(payment.getCardLast4Snapshot()).isEqualTo("1234");
    }

    /** 예약을 생성해 진료완료(true) 또는 승인 상태(false)까지 전이시키고 저장한 뒤 id를 반환한다. */
    private Long persistReservation(boolean treatmentCompleted) {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = Reservation.request(
                guardianMemberId, 1L, HOSPITAL_ID, System.nanoTime(), paymentMethodId, "나비", "CAT", now);
        // #74가 예약 상태 전이 엔티티 메서드를 제거하고 조건부 UPDATE로 단일화했으므로,
        // 테스트 픽스처는 상태 필드를 직접 세팅해 원하는 시작 상태를 만든다.
        ReflectionTestUtils.setField(reservation, "status",
                treatmentCompleted
                        ? ReservationStatus.TREATMENT_COMPLETED
                        : ReservationStatus.CONFIRMED);
        ReflectionTestUtils.setField(reservation, "confirmedAt", now);
        Long reservationId = reservationRepository.saveAndFlush(reservation).getId();
        reservationIds.add(reservationId);
        if (treatmentCompleted) {
            // 청구 가능한 예약에는 초안 항목을 함께 깐다 — 청구는 초안 합계로 총액을 산출한다(SA §9-4 청구 항목).
            persistDraft(paymentItemRepository, reservationId, AMOUNT);
        }
        return reservationId;
    }
}
