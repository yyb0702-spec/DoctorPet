package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Level 1 — 청구 전제 검증·선기록·후확정 단위 검증(트랜잭션 경계 서비스). 외부 승인·재시도 분기는
 * PaymentApplicationServiceTest가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentChargeServiceTest {

    private static final Long RESERVATION_ID = 100L;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;
    private static final Long PAYMENT_METHOD_ID = 7L;
    private static final int MAX_AMOUNT = 3_000_000;
    private static final int VALID_AMOUNT = 50_000;

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private ReservationLookupPort reservationLookupPort;
    @Mock private StaffHospitalPort staffHospitalPort;
    @Mock private MerchantPaymentIdGenerator merchantPaymentIdGenerator;

    private PaymentChargeService paymentChargeService;

    @BeforeEach
    void setUp() {
        paymentChargeService = new PaymentChargeService(
                paymentRepository, paymentMethodRepository, reservationLookupPort,
                staffHospitalPort, merchantPaymentIdGenerator, MAX_AMOUNT);
    }

    private void stubChargeableReservation() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true)));
    }

    @Nested
    @DisplayName("preRecord 검증")
    class PreRecord {

        @Test
        @DisplayName("정상 요청은 PENDING 선기록하고 카드 스냅샷·멱등키·활성 여부를 담아 반환한다")
        void success() {
            stubChargeableReservation();
            given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);
            given(merchantPaymentIdGenerator.generate()).willReturn("pay_test");
            given(paymentMethodRepository.findByIdAndMemberId(PAYMENT_METHOD_ID, GUARDIAN_ID))
                    .willReturn(Optional.of(PaymentMethod.issue(GUARDIAN_ID, "v1:enc", "VISA", "1234")));

            PaymentPreRecord pre = paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT);

            assertThat(pre.merchantPaymentId()).isEqualTo("pay_test");
            assertThat(pre.billingKeyEnc()).isEqualTo("v1:enc");
            assertThat(pre.amount()).isEqualTo(VALID_AMOUNT);
            assertThat(pre.paymentMethodActive()).isTrue();
            assertThat(pre.guardianMemberId()).isEqualTo(GUARDIAN_ID);

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).saveAndFlush(captor.capture());
            Payment saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(saved.getCardBrandSnapshot()).isEqualTo("VISA");
            assertThat(saved.getCardLast4Snapshot()).isEqualTo("1234");
            assertThat(saved.getReservationId()).isEqualTo(RESERVATION_ID);
        }

        @Test
        @DisplayName("삭제·만료된 결제수단이면 선기록은 하되 비활성으로 표시한다(게이트웨이 호출은 상위가 건너뛴다)")
        void inactivePaymentMethod_marksInactive() {
            stubChargeableReservation();
            given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);
            given(merchantPaymentIdGenerator.generate()).willReturn("pay_test");
            PaymentMethod deleted = PaymentMethod.issue(GUARDIAN_ID, "v1:enc", "VISA", "1234");
            deleted.markDeleted();
            given(paymentMethodRepository.findByIdAndMemberId(PAYMENT_METHOD_ID, GUARDIAN_ID))
                    .willReturn(Optional.of(deleted));

            PaymentPreRecord pre = paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT);

            assertThat(pre.paymentMethodActive()).isFalse();
        }

        @Test
        @DisplayName("스태프에게 소속 병원이 없으면 FORBIDDEN_HOSPITAL")
        void noStaffHospital_forbidden() {
            given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
            verify(paymentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("예약 병원과 스태프 소속 병원이 다르면 FORBIDDEN_HOSPITAL(타병원)")
        void otherHospital_forbidden() {
            given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(999L));
            given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                    new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true)));

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }

        @Test
        @DisplayName("진료 완료 상태가 아니면 RESERVATION_NOT_CHARGEABLE")
        void notCompleted_notChargeable() {
            given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
            given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                    new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, false)));

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        }

        @Test
        @DisplayName("금액이 절대 상한을 넘으면 INVALID_AMOUNT")
        void overMax_invalidAmount() {
            stubChargeableReservation();

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, MAX_AMOUNT + 1))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
        }

        @Test
        @DisplayName("예약당 이미 결제가 있으면 DUPLICATE_CHARGE(사전 체크)")
        void alreadyCharged_duplicate() {
            stubChargeableReservation();
            given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(true);

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.DUPLICATE_CHARGE);
            verify(paymentRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("finalizeOutcome 상태 확정")
    class FinalizeOutcome {

        @Test
        @DisplayName("PAID 결과는 PAID·BILLING_KEY·pgPaymentId·paidAt로 확정한다")
        void paid() {
            Payment payment = Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

            paymentChargeService.finalizeOutcome(1L,
                    ChargeOutcome.paid("PG-1", LocalDateTime.of(2026, 7, 28, 10, 0)));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(payment.getPgPaymentId()).isEqualTo("PG-1");
            assertThat(payment.getPaidAt()).isNotNull();
        }

        @Test
        @DisplayName("OFFLINE_REQUIRED 결과는 사유·재시도 횟수와 함께 오프라인 대상으로 확정한다")
        void offlineRequired() {
            Payment payment = Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

            paymentChargeService.finalizeOutcome(1L, ChargeOutcome.offlineRequired("NON_RETRIABLE", 2));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.OFFLINE_REQUIRED);
            assertThat(payment.getFailureReason()).isEqualTo("NON_RETRIABLE");
            assertThat(payment.getRetryCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("PENDING 결과는 상태를 PENDING으로 유지하고 재시도 횟수·사유만 갱신한다")
        void pending() {
            Payment payment = Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

            paymentChargeService.finalizeOutcome(1L, ChargeOutcome.pending(1, "UNCONFIRMED_TIMEOUT"));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getFailureReason()).isEqualTo("UNCONFIRMED_TIMEOUT");
        }

        @Test
        @DisplayName("결제 레코드가 없으면 PAYMENT_NOT_FOUND")
        void notFound() {
            given(paymentRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentChargeService.finalizeOutcome(1L,
                    ChargeOutcome.paid("PG-1", LocalDateTime.now())))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
    }
}
