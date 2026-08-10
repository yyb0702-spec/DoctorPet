package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Level 1 — 결제 내역 조회 권한·응답 단위 검증(#47). 예약 소유권·병원 정보는 port 목으로 주입한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    private static final Long RESERVATION_ID = 100L;
    private static final Long GUARDIAN_ID = 5L;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long PAYMENT_METHOD_ID = 7L;

    @Mock private ReservationLookupPort reservationLookupPort;
    @Mock private StaffHospitalPort staffHospitalPort;
    @Mock private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentQueryService paymentQueryService;

    private ReservationChargeView reservation() {
        return new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true);
    }

    private Payment paidPayment() {
        Payment payment = Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", 50_000);
        payment.markPaid("PG-1", LocalDateTime.now());
        return payment;
    }

    @Test
    @DisplayName("리뷰 작성용 결제 조회는 잠금 조회 결과의 상태를 반환한다")
    void findStatusForUpdate_returnsLockedPaymentStatus() {
        given(paymentRepository.findByReservationIdForUpdate(RESERVATION_ID))
                .willReturn(Optional.of(paidPayment()));

        Optional<PaymentStatus> status = paymentQueryService
                .findStatusByReservationIdForUpdate(RESERVATION_ID);

        assertThat(status).contains(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("보호자 본인 예약의 결제가 있으면 1건을 표시용 카드정보와 함께 반환한다")
    void guardian_success() {
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(reservation()));
        given(paymentRepository.findByReservationId(RESERVATION_ID)).willReturn(Optional.of(paidPayment()));

        List<PaymentHistoryResponse> result = paymentQueryService.getForGuardian(RESERVATION_ID, GUARDIAN_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.get(0).cardLast4Snapshot()).isEqualTo("1234");
        assertThat(result.get(0).amount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("결제 전이면 예약은 있어도 빈 내역을 반환한다")
    void guardian_emptyBeforeCharge() {
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(reservation()));
        given(paymentRepository.findByReservationId(RESERVATION_ID)).willReturn(Optional.empty());

        assertThat(paymentQueryService.getForGuardian(RESERVATION_ID, GUARDIAN_ID)).isEmpty();
    }

    @Test
    @DisplayName("본인 예약이 아니면 403(FORBIDDEN)")
    void guardian_otherMember_forbidden() {
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(reservation()));

        assertThatThrownBy(() -> paymentQueryService.getForGuardian(RESERVATION_ID, 999L))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("예약이 없으면 404(NOT_FOUND)")
    void guardian_reservationNotFound() {
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentQueryService.getForGuardian(RESERVATION_ID, GUARDIAN_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("병원 스태프는 자병원 예약의 결제를 조회한다")
    void hospital_success() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(reservation()));
        given(paymentRepository.findByReservationId(RESERVATION_ID)).willReturn(Optional.of(paidPayment()));

        List<PaymentHistoryResponse> result = paymentQueryService.getForHospital(RESERVATION_ID, STAFF_MEMBER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("타병원 예약이면 403(FORBIDDEN)")
    void hospital_otherHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(999L));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(reservation()));

        assertThatThrownBy(() -> paymentQueryService.getForHospital(RESERVATION_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("소속 병원이 없는 계정이면 403(FORBIDDEN)")
    void hospital_noStaffHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentQueryService.getForHospital(RESERVATION_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
    }
}
