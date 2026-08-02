package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 1 — 오프라인 정산 전이·권한·멱등·동시성 판정 단위 검증(#36). 조건부 UPDATE의 실제 원자성은
 * Level 3 동시성 테스트가 확인하고, 여기서는 갱신 행 수에 따른 분기(성립·멱등·거부)를 목으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOfflineSettleTxServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;

    @Mock private PaymentRepository paymentRepository;
    @Mock private ReservationLookupPort reservationLookupPort;
    @Mock private StaffHospitalPort staffHospitalPort;

    @InjectMocks
    private PaymentOfflineSettleTxService service;

    private Payment payment(PaymentStatus status) {
        Payment p = Payment.pending(RESERVATION_ID, "pay_x", 7L, "VISA", "1234", 50_000);
        if (status == PaymentStatus.OFFLINE_REQUIRED) {
            p.markOfflineRequired("NON_RETRIABLE", 0);
        } else if (status != PaymentStatus.PENDING) {
            ReflectionTestUtils.setField(p, "status", status);
        }
        return p;
    }

    private void stubOwnHospital() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, 7L, true)));
    }

    @Test
    @DisplayName("OFFLINE_REQUIRED를 자병원 스태프가 정산하면 조건부 UPDATE 1건으로 OFFLINE_PAID 확정(freshlySettled)")
    void settle_success() {
        stubOwnHospital();
        given(paymentRepository.findById(PAYMENT_ID))
                .willReturn(Optional.of(payment(PaymentStatus.OFFLINE_REQUIRED)),
                        Optional.of(payment(PaymentStatus.OFFLINE_PAID)));
        given(paymentRepository.settleOfflineIfRequired(anyLong(), any(), anyLong())).willReturn(1);

        OfflineSettleOutcome outcome = service.settle(PAYMENT_ID, STAFF_MEMBER_ID);

        assertThat(outcome.freshlySettled()).isTrue();
        assertThat(outcome.response().status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        assertThat(outcome.guardianMemberId()).isEqualTo(GUARDIAN_ID);
    }

    @Test
    @DisplayName("이미 OFFLINE_PAID면 변경 없이 멱등 반환하고 조건부 UPDATE를 호출하지 않는다")
    void alreadyPaid_idempotent() {
        stubOwnHospital();
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment(PaymentStatus.OFFLINE_PAID)));

        OfflineSettleOutcome outcome = service.settle(PAYMENT_ID, STAFF_MEMBER_ID);

        assertThat(outcome.freshlySettled()).isFalse();
        assertThat(outcome.response().status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        verify(paymentRepository, never()).settleOfflineIfRequired(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("PENDING·PAID 등 허용되지 않은 상태면 OFFLINE_PRECONDITION_FAILED(409)")
    void notOfflineRequired_precondition() {
        stubOwnHospital();
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment(PaymentStatus.PAID)));

        assertThatThrownBy(() -> service.settle(PAYMENT_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.OFFLINE_PRECONDITION_FAILED);
        verify(paymentRepository, never()).settleOfflineIfRequired(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("조건부 UPDATE가 0건이지만 재조회가 OFFLINE_PAID면 동시 정산으로 보고 멱등 반환한다")
    void concurrentLost_idempotent() {
        stubOwnHospital();
        given(paymentRepository.findById(PAYMENT_ID))
                .willReturn(Optional.of(payment(PaymentStatus.OFFLINE_REQUIRED)),
                        Optional.of(payment(PaymentStatus.OFFLINE_PAID)));
        given(paymentRepository.settleOfflineIfRequired(anyLong(), any(), anyLong())).willReturn(0);

        OfflineSettleOutcome outcome = service.settle(PAYMENT_ID, STAFF_MEMBER_ID);

        assertThat(outcome.freshlySettled()).isFalse();
        assertThat(outcome.response().status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
    }

    @Test
    @DisplayName("소속 병원이 없는 계정이면 FORBIDDEN_HOSPITAL")
    void noStaffHospital_forbidden() {
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment(PaymentStatus.OFFLINE_REQUIRED)));
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.settle(PAYMENT_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
    }

    @Test
    @DisplayName("타병원 예약의 결제면 FORBIDDEN_HOSPITAL")
    void otherHospital_forbidden() {
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment(PaymentStatus.OFFLINE_REQUIRED)));
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, 999L, GUARDIAN_ID, 7L, true)));

        assertThatThrownBy(() -> service.settle(PAYMENT_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
    }

    @Test
    @DisplayName("결제가 없으면 PAYMENT_NOT_FOUND")
    void notFound() {
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.settle(PAYMENT_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_NOT_FOUND);
    }
}
