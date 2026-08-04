package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;

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

    // JVM 기본 시간대에 의존하지 않도록 고정 Clock을 주입한다(운영은 서울 기준 applicationClock).
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    private PaymentChargeService paymentChargeService;

    @BeforeEach
    void setUp() {
        paymentChargeService = new PaymentChargeService(
                paymentRepository, paymentMethodRepository, reservationLookupPort,
                staffHospitalPort, merchantPaymentIdGenerator, FIXED_CLOCK, MAX_AMOUNT);
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

        @Test
        @DisplayName("선기록이 reservation_id UNIQUE 위반(23000/1062)이면 사전 체크를 통과한 경쟁으로 보고 DUPLICATE_CHARGE (#83, #90)")
        void saveReservationIdUniqueViolation_duplicate() {
            stubSaveThrows(new SQLException(
                    "Duplicate entry '100' for key 'payments.uk_payments_reservation_id'", "23000", 1062));

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.DUPLICATE_CHARGE);
        }

        @Test
        @DisplayName("선기록이 merchant_payment_id UNIQUE 위반이면 예약엔 결제가 없으므로 DUPLICATE_CHARGE로 오분류하지 않고 원 예외를 전파한다 (#90)")
        void saveMerchantIdUniqueViolation_propagates() {
            stubSaveThrows(new SQLException(
                    "Duplicate entry 'pay_test' for key 'payments.uk_payments_merchant_payment_id'", "23000", 1062));

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("선기록이 UNIQUE가 아닌 무결성 위반(NOT NULL 23000/1048)이면 DUPLICATE_CHARGE로 오분류하지 않고 원 예외를 전파한다 (#83)")
        void saveNonUniqueViolation_propagates() {
            stubSaveThrows(new SQLException("Column cannot be null", "23000", 1048));

            assertThatThrownBy(() -> paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, VALID_AMOUNT))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        // saveAndFlush까지 도달하도록 정상 경로를 스텁하고, 저장에서 주어진 SQLException을 감싼 무결성 위반을 던진다.
        private void stubSaveThrows(SQLException cause) {
            stubChargeableReservation();
            given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);
            given(merchantPaymentIdGenerator.generate()).willReturn("pay_test");
            given(paymentMethodRepository.findByIdAndMemberId(PAYMENT_METHOD_ID, GUARDIAN_ID))
                    .willReturn(Optional.of(PaymentMethod.issue(GUARDIAN_ID, "v1:enc", "VISA", "1234")));
            given(paymentRepository.saveAndFlush(any()))
                    .willThrow(new DataIntegrityViolationException("save failed", cause));
        }
    }

    @Nested
    @DisplayName("finalizeOutcome 조건부 상태 확정")
    class FinalizeOutcome {

        @Test
        @DisplayName("PAID 결과는 WHERE status='PENDING' 조건부 UPDATE로 전이하고, 1건 갱신되면 applied=true다")
        void paid_applied() {
            LocalDateTime paidAt = LocalDateTime.of(2026, 7, 28, 10, 0);
            given(paymentRepository.markPaidIfPending(eq(1L), eq("PG-1"), eq(paidAt), any(LocalDateTime.class)))
                    .willReturn(1);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(
                    Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT)));

            PaymentChargeService.FinalizeResult result =
                    paymentChargeService.finalizeOutcome(1L, ChargeOutcome.paid("PG-1", paidAt));

            assertThat(result.applied()).isTrue();
            assertThat(result.payment()).isNotNull();
            verify(paymentRepository).markPaidIfPending(eq(1L), eq("PG-1"), eq(paidAt), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("OFFLINE_REQUIRED 결과는 사유·재시도 횟수로 조건부 전이하고, 1건 갱신되면 applied=true다")
        void offlineRequired_applied() {
            given(paymentRepository.markOfflineRequiredIfPending(eq(1L), eq("NON_RETRIABLE"), eq(2), any(LocalDateTime.class)))
                    .willReturn(1);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(
                    Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT)));

            PaymentChargeService.FinalizeResult result =
                    paymentChargeService.finalizeOutcome(1L, ChargeOutcome.offlineRequired("NON_RETRIABLE", 2));

            assertThat(result.applied()).isTrue();
            verify(paymentRepository).markOfflineRequiredIfPending(eq(1L), eq("NON_RETRIABLE"), eq(2), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("PENDING 유지는 상태 전이가 아니므로 재시도 수·사유만 갱신하고 applied=false다(알림 대상 아님)")
        void pending_notApplied() {
            given(paymentRepository.remainPendingIfPending(eq(1L), eq(1), eq("UNCONFIRMED_TIMEOUT"), any(LocalDateTime.class)))
                    .willReturn(1);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(
                    Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT)));

            PaymentChargeService.FinalizeResult result =
                    paymentChargeService.finalizeOutcome(1L, ChargeOutcome.pending(1, "UNCONFIRMED_TIMEOUT"));

            assertThat(result.applied()).isFalse();
            verify(paymentRepository).remainPendingIfPending(eq(1L), eq(1), eq("UNCONFIRMED_TIMEOUT"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("이미 다른 경로가 확정해 조건부 UPDATE가 0건이면 덮어쓰지 않고 applied=false로 현재 상태를 반환한다")
        void alreadyFinalized_notApplied() {
            // 정산이 먼저 OFFLINE_REQUIRED로 확정한 뒤 청구 후확정이 PAID를 시도하는 경합 상황.
            given(paymentRepository.markPaidIfPending(eq(1L), any(), any(), any(LocalDateTime.class))).willReturn(0);
            Payment alreadyOffline = Payment.pending(RESERVATION_ID, "pay_x", PAYMENT_METHOD_ID, "VISA", "1234", VALID_AMOUNT);
            alreadyOffline.markOfflineRequired("RECONCILE_FAILED", 0);
            given(paymentRepository.findById(1L)).willReturn(Optional.of(alreadyOffline));

            PaymentChargeService.FinalizeResult result =
                    paymentChargeService.finalizeOutcome(1L, ChargeOutcome.paid("PG-1", LocalDateTime.now()));

            assertThat(result.applied()).isFalse();
            assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.OFFLINE_REQUIRED);
        }

        @Test
        @DisplayName("결제 레코드가 없으면 PAYMENT_NOT_FOUND")
        void notFound() {
            given(paymentRepository.markPaidIfPending(eq(1L), any(), any(), any(LocalDateTime.class))).willReturn(0);
            given(paymentRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentChargeService.finalizeOutcome(1L,
                    ChargeOutcome.paid("PG-1", LocalDateTime.now())))
                    .isInstanceOf(ServiceException.class)
                    .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
    }
}
