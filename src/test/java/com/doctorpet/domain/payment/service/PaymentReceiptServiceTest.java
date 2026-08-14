package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.response.PaymentItemResponse;
import com.doctorpet.domain.payment.dto.response.PaymentReceiptResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.entity.PaymentRefund;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.RefundStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.ReservationReceiptView;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentRefundRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 1 — JSON 영수증 조회의 권한·상태·노출 필드 단위 검증(SA §9-4 영수증).
 * 실제 MySQL 저장·항목 정합은 PaymentItemChargeIntegrationTest(Level 3)가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentReceiptServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long HOSPITAL_ID = 7L;
    private static final Long GUARDIAN_ID = 5L;
    private static final Long STAFF_MEMBER_ID = 9L;

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private PaymentRefundRepository paymentRefundRepository;
    @Mock private ReservationLookupPort reservationLookupPort;
    @Mock private StaffHospitalPort staffHospitalPort;

    @InjectMocks private PaymentReceiptService paymentReceiptService;

    @Test
    @DisplayName("보호자 본인 결제는 항목·총액·카드 스냅샷·펫 스냅샷을 담은 영수증을 반환한다")
    void guardian_paidPayment_returnsReceipt() {
        Payment payment = paidPayment(50_000);
        stubPaymentAndReservation(payment);
        given(paymentItemRepository.findByPaymentIdOrderByIdAsc(PAYMENT_ID)).willReturn(List.of(
                PaymentItem.draft(RESERVATION_ID, "진찰료", 1, 20_000, 20_000),
                PaymentItem.draft(RESERVATION_ID, "주사", 2, 15_000, 30_000)));

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID);

        assertThat(receipt.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(receipt.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(receipt.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(receipt.guardianMemberId()).isEqualTo(GUARDIAN_ID);
        assertThat(receipt.petName()).isEqualTo("나비");
        assertThat(receipt.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(receipt.paymentChannel()).isEqualTo(PaymentChannel.BILLING_KEY);
        assertThat(receipt.cardBrandSnapshot()).isEqualTo("VISA");
        assertThat(receipt.cardLast4Snapshot()).isEqualTo("1234");
        assertThat(receipt.totalAmount()).isEqualTo(50_000);
        assertThat(receipt.paidAt()).isNotNull();
        assertThat(receipt.items())
                .extracting(PaymentItemResponse::name, PaymentItemResponse::quantity,
                        PaymentItemResponse::unitPrice, PaymentItemResponse::amount)
                .containsExactly(
                        tuple("진찰료", 1, 20_000, 20_000),
                        tuple("주사", 2, 15_000, 30_000));
        // 환불되지 않은 결제는 환불 이력을 조회하지 않는다(진행 중·실패한 내부 처리 상태를 노출하지 않기 위함).
        assertThat(receipt.refundStatus()).isNull();
        assertThat(receipt.refundedAt()).isNull();
        verify(paymentRefundRepository, never()).findByPaymentId(PAYMENT_ID);
    }

    @Test
    @DisplayName("할인 항목이 음수 단가·음수 금액 그대로 영수증에 노출된다")
    void guardian_discountItem_exposedAsNegative() {
        Payment payment = paidPayment(15_000);
        stubPaymentAndReservation(payment);
        given(paymentItemRepository.findByPaymentIdOrderByIdAsc(PAYMENT_ID)).willReturn(List.of(
                PaymentItem.draft(RESERVATION_ID, "진찰료", 1, 20_000, 20_000),
                PaymentItem.draft(RESERVATION_ID, "재진 할인", 1, -5_000, -5_000)));

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID);

        assertThat(receipt.items()).extracting(PaymentItemResponse::amount)
                .containsExactly(20_000, -5_000);
        assertThat(receipt.totalAmount()).isEqualTo(15_000);
    }

    @Test
    @DisplayName("항목화 이전에 청구된 결제는 빈 항목 목록으로 안전하게 조회되고 총액은 payments.amount를 그대로 쓴다")
    void guardian_legacyPaymentWithoutItems_returnsEmptyItems() {
        Payment payment = paidPayment(50_000);
        stubPaymentAndReservation(payment);
        given(paymentItemRepository.findByPaymentIdOrderByIdAsc(PAYMENT_ID)).willReturn(List.of());

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID);

        assertThat(receipt.items()).isEmpty();
        assertThat(receipt.totalAmount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("현장 수납(OFFLINE_PAID)도 영수증 발급 대상이며 수납 시각을 표시한다")
    void guardian_offlinePaid_returnsReceipt() {
        Payment payment = Payment.pending(RESERVATION_ID, "pay_1", 7L, "VISA", "1234", 50_000);
        payment.markOfflineRequired("NON_RETRIABLE", 0);
        setField(payment, "status", PaymentStatus.OFFLINE_PAID);
        setField(payment, "paymentChannel", PaymentChannel.OFFLINE);
        setField(payment, "offlineSettledAt", LocalDateTime.of(2026, 8, 10, 10, 0));
        stubPaymentAndReservation(payment);
        given(paymentItemRepository.findByPaymentIdOrderByIdAsc(PAYMENT_ID)).willReturn(List.of());

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID);

        assertThat(receipt.status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        assertThat(receipt.offlineSettledAt()).isEqualTo(LocalDateTime.of(2026, 8, 10, 10, 0));
        assertThat(receipt.paidAt()).isNull();
    }

    @Test
    @DisplayName("REFUNDED 영수증은 환불 상태·환불 일시를 표시하되 환불 사유는 담지 않는다(감사용 내부 정보)")
    void guardian_refunded_showsRefundStatusWithoutReason() {
        Payment payment = paidPayment(50_000);
        LocalDateTime refundedAt = LocalDateTime.of(2026, 8, 11, 9, 30);
        payment.markRefunded(refundedAt);
        stubPaymentAndReservation(payment);
        given(paymentItemRepository.findByPaymentIdOrderByIdAsc(PAYMENT_ID)).willReturn(List.of());
        PaymentRefund refund = PaymentRefund.requested(
                PAYMENT_ID, "refund_1", 50_000, "금액 오입력", STAFF_MEMBER_ID, refundedAt, "token");
        refund.markCompleted("PG-CANCEL-1", refundedAt);
        given(paymentRefundRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.of(refund));

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID);

        assertThat(receipt.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(receipt.refundStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(receipt.refundedAt()).isEqualTo(refundedAt);
        // 응답 레코드 어디에도 환불 사유가 담기지 않는지 필드 단위로 확인한다(필드가 늘어도 회귀를 잡는다).
        assertThat(fieldValues(receipt)).noneMatch(value -> value.contains("금액 오입력"));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "OFFLINE_REQUIRED"})
    @DisplayName("결제가 확정되지 않은 상태(PENDING·OFFLINE_REQUIRED)는 RECEIPT_NOT_AVAILABLE(409)")
    void guardian_unsettledStatus_rejected(PaymentStatus status) {
        Payment payment = Payment.pending(RESERVATION_ID, "pay_1", 7L, "VISA", "1234", 50_000);
        setField(payment, "status", status);
        stubPaymentAndReservation(payment);

        assertThatThrownBy(() -> paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.RECEIPT_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("보호자가 타인의 결제를 조회하면 403이고, 상태 확인 이전에 막혀 항목도 읽지 않는다")
    void guardian_otherMembersPayment_forbidden() {
        Payment payment = paidPayment(50_000);
        stubPaymentAndReservation(payment);

        assertThatThrownBy(() -> paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID + 1))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        verify(paymentItemRepository, never()).findByPaymentIdOrderByIdAsc(PAYMENT_ID);
    }

    @Test
    @DisplayName("병원 스태프는 자병원 결제 영수증을 조회한다")
    void hospital_ownHospital_returnsReceipt() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        Payment payment = paidPayment(50_000);
        stubPaymentAndReservation(payment);
        given(paymentItemRepository.findByPaymentIdOrderByIdAsc(PAYMENT_ID)).willReturn(List.of(
                PaymentItem.draft(RESERVATION_ID, "진찰료", 1, 50_000, 50_000)));

        PaymentReceiptResponse receipt = paymentReceiptService.getForHospital(PAYMENT_ID, STAFF_MEMBER_ID);

        assertThat(receipt.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(receipt.items()).hasSize(1);
    }

    @Test
    @DisplayName("타 병원 스태프가 조회하면 403이고 항목도 읽지 않는다")
    void hospital_otherHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID + 1));
        Payment payment = paidPayment(50_000);
        stubPaymentAndReservation(payment);

        assertThatThrownBy(() -> paymentReceiptService.getForHospital(PAYMENT_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        verify(paymentItemRepository, never()).findByPaymentIdOrderByIdAsc(PAYMENT_ID);
    }

    @Test
    @DisplayName("소속 병원이 없는 계정은 결제를 읽기 전에 403으로 막힌다")
    void hospital_noHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentReceiptService.getForHospital(PAYMENT_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        verify(paymentRepository, never()).findById(PAYMENT_ID);
    }

    @Test
    @DisplayName("결제가 없으면 PAYMENT_NOT_FOUND(404)")
    void paymentNotFound() {
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentReceiptService.getForGuardian(PAYMENT_ID, GUARDIAN_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_NOT_FOUND);
    }

    private Payment paidPayment(int amount) {
        Payment payment = Payment.pending(RESERVATION_ID, "pay_1", 7L, "VISA", "1234", amount);
        payment.markPaid("PG-1", LocalDateTime.of(2026, 8, 10, 10, 0));
        return payment;
    }

    private void stubPaymentAndReservation(Payment payment) {
        setField(payment, "id", PAYMENT_ID);
        given(paymentRepository.findById(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(reservationLookupPort.findForReceipt(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationReceiptView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, 11L, "나비", "CAT")));
    }

    private void setField(Object target, String name, Object value) {
        ReflectionTestUtils.setField(target, name, value);
    }

    /** 응답 레코드의 모든 필드 값을 문자열로 모은다 — 특정 값이 어느 필드로도 새지 않는지 확인하는 용도다. */
    private List<String> fieldValues(PaymentReceiptResponse receipt) {
        return java.util.Arrays.stream(PaymentReceiptResponse.class.getRecordComponents())
                .map(component -> {
                    try {
                        Field field = PaymentReceiptResponse.class.getDeclaredField(component.getName());
                        field.setAccessible(true);
                        return String.valueOf(field.get(receipt));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
