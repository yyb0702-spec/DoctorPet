package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 3 — 결제 내역 조회 통합 검증(#47). 목 없이 실제 배선을 그대로 탄다:
 * 예약 조회(ReservationLookupPort → ReservationChargeLookupAdapter → ReservationRepository)와
 * 스태프 소속 병원(StaffHospitalPort → MemberStaffHospitalAdapter → MemberService)을 실제 빈으로 연결하고,
 * 실 MySQL에 저장된 예약·결제로 보호자/병원 조회·권한·빈 내역을 확인한다. 전체 컨텍스트(MySQL·Redis·env) 필요.
 */
@SpringBootTest
class PaymentQueryIntegrationTest {

    private static final Long HOSPITAL_ID = 7777L;

    @Autowired private PaymentQueryService paymentQueryService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;

    private Long staffMemberId;
    private Long guardianMemberId;
    private Long paymentMethodId;
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> paymentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
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
        paymentIds.forEach(paymentRepository::deleteById);
        reservationIds.forEach(reservationRepository::deleteById);
        paymentMethodRepository.deleteById(paymentMethodId);
        memberRepository.deleteById(staffMemberId);
        memberRepository.deleteById(guardianMemberId);
    }

    @Test
    @DisplayName("보호자·병원 모두 실제 예약·결제를 조회하고 표시용 카드정보를 받는다")
    void guardianAndHospital_readSamePayment() {
        Long reservationId = persistReservation();
        persistPaidPayment(reservationId);

        List<PaymentHistoryResponse> byGuardian = paymentQueryService.getForGuardian(reservationId, guardianMemberId);
        assertThat(byGuardian).hasSize(1);
        assertThat(byGuardian.get(0).status()).isEqualTo(PaymentStatus.PAID);
        assertThat(byGuardian.get(0).cardLast4Snapshot()).isEqualTo("1234");

        List<PaymentHistoryResponse> byHospital = paymentQueryService.getForHospital(reservationId, staffMemberId);
        assertThat(byHospital).hasSize(1);
        assertThat(byHospital.get(0).paymentId()).isEqualTo(byGuardian.get(0).paymentId());
    }

    @Test
    @DisplayName("본인 예약이 아니면 403(FORBIDDEN)")
    void guardian_otherMember_forbidden() {
        Long reservationId = persistReservation();
        persistPaidPayment(reservationId);

        assertThatThrownBy(() -> paymentQueryService.getForGuardian(reservationId, guardianMemberId + 99_999))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("예약은 있어도 결제 전이면 빈 내역을 반환한다")
    void emptyBeforeCharge() {
        Long reservationId = persistReservation();

        assertThat(paymentQueryService.getForGuardian(reservationId, guardianMemberId)).isEmpty();
    }

    private Long persistReservation() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = Reservation.request(
                guardianMemberId, 1L, HOSPITAL_ID, System.nanoTime(), paymentMethodId, "나비", "CAT", now);
        // #74가 상태 전이 메서드를 조건부 UPDATE로 단일화했으므로 픽스처는 상태 필드를 직접 세팅한다(E2E와 동일).
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.TREATMENT_COMPLETED);
        ReflectionTestUtils.setField(reservation, "confirmedAt", now);
        Long reservationId = reservationRepository.saveAndFlush(reservation).getId();
        reservationIds.add(reservationId);
        return reservationId;
    }

    private void persistPaidPayment(Long reservationId) {
        Payment payment = Payment.pending(
                reservationId, "pay_" + reservationId, paymentMethodId, "VISA", "1234", 50_000);
        payment.markPaid("PG-" + reservationId, LocalDateTime.now());
        paymentIds.add(paymentRepository.saveAndFlush(payment).getId());
    }
}
