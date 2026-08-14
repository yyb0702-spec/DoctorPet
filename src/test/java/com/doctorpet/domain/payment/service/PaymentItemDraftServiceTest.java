package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.response.PaymentItemDraftResponse;
import com.doctorpet.domain.payment.dto.response.PaymentItemResponse;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Level 1 — 청구 항목 초안 작성·조회 계약 단위 검증(SA §9-4 청구 항목).
 * 실제 MySQL에서의 조건부 쓰기·청구와의 직렬화는 PaymentItemChargeIntegrationTest(Level 3)가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentItemDraftServiceTest {

    private static final Long RESERVATION_ID = 100L;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;
    private static final Long PAYMENT_METHOD_ID = 7L;
    private static final int MAX_AMOUNT = 3_000_000;

    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private ReservationLookupPort reservationLookupPort;
    @Mock private StaffHospitalPort staffHospitalPort;

    private PaymentItemDraftService paymentItemDraftService;

    @BeforeEach
    void setUp() {
        paymentItemDraftService = new PaymentItemDraftService(
                paymentItemRepository, paymentRepository, reservationLookupPort, staffHospitalPort,
                new PaymentAmountPolicy(MAX_AMOUNT));
    }

    @Test
    @DisplayName("진료 완료된 자병원 예약의 초안을 전체 교체하고, 항목 금액은 서버가 수량×단가로 산출한다")
    void replaceDrafts_savesServerCalculatedAmounts() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);
        given(paymentItemRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        PaymentItemDraftResponse savedResponse = paymentItemDraftService.replaceDrafts(
                RESERVATION_ID, STAFF_MEMBER_ID, List.of(
                        new PaymentItemCommand("진찰료", 1, 20_000),
                        new PaymentItemCommand("주사", 2, 15_000)));

        assertThat(savedResponse.items())
                .extracting(PaymentItemResponse::name, PaymentItemResponse::quantity,
                        PaymentItemResponse::unitPrice, PaymentItemResponse::amount)
                .containsExactly(
                        tuple("진찰료", 1, 20_000, 20_000),
                        tuple("주사", 2, 15_000, 30_000));
        // 전체 교체이므로 기존 초안을 조건부 삭제한 뒤 새로 저장한다.
        verify(paymentItemRepository).deleteDraftsByReservationId(RESERVATION_ID);
        assertThat(savedItems()).allMatch(PaymentItem::isDraft);
        assertThat(savedItems()).extracting(PaymentItem::getReservationId)
                .containsOnly(RESERVATION_ID);
    }

    @Test
    @DisplayName("음수 단가의 할인 항목을 포함해도 합계가 양수면 초안으로 저장된다")
    void replaceDrafts_allowsDiscountItem() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);
        given(paymentItemRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        PaymentItemDraftResponse savedResponse = paymentItemDraftService.replaceDrafts(
                RESERVATION_ID, STAFF_MEMBER_ID, List.of(
                        new PaymentItemCommand("진찰료", 1, 20_000),
                        new PaymentItemCommand("재진 할인", 1, -5_000)));

        assertThat(savedResponse.items()).extracting(PaymentItemResponse::amount).containsExactly(20_000, -5_000);
    }

    @Test
    @DisplayName("이미 청구가 시작된 예약이면 PAYMENT_ITEM_ALREADY_CHARGED(409)이고 아무것도 쓰지 않는다")
    void replaceDrafts_alreadyCharged_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(true);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(
                RESERVATION_ID, STAFF_MEMBER_ID, List.of(new PaymentItemCommand("진찰료", 1, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED);
        verify(paymentItemRepository, never()).deleteDraftsByReservationId(anyLong());
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("빈 목록이면 INVALID_PAYMENT_ITEM")
    void replaceDrafts_emptyItems_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() ->
                paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID, List.of()))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_PAYMENT_ITEM);
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("수량이 0이거나 음수면 INVALID_PAYMENT_ITEM(요청 DTO @Positive를 우회한 경로까지 막는다)")
    void replaceDrafts_nonPositiveQuantity_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("진찰료", 0, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_PAYMENT_ITEM);
        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("진찰료", -1, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_PAYMENT_ITEM);
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("항목명이 비어 있으면 INVALID_PAYMENT_ITEM")
    void replaceDrafts_blankName_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("  ", 1, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_PAYMENT_ITEM);
    }

    @Test
    @DisplayName("할인이 커서 합계가 0 이하가 되면 INVALID_AMOUNT — 청구할 수 없는 구성은 초안으로도 남기지 않는다")
    void replaceDrafts_nonPositiveTotal_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID, List.of(
                new PaymentItemCommand("진찰료", 1, 20_000),
                new PaymentItemCommand("전액 할인", 1, -20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("항목 하나의 수량×단가가 int 범위를 넘으면 INVALID_AMOUNT(오버플로가 음수로 되감기지 않는다)")
    void replaceDrafts_lineAmountOverflow_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("과다 항목", 2, Integer.MAX_VALUE))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("항목별로는 int 범위 안이어도 합계가 넘치면 INVALID_AMOUNT")
    void replaceDrafts_totalOverflow_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID, List.of(
                new PaymentItemCommand("항목1", 1, Integer.MAX_VALUE),
                new PaymentItemCommand("항목2", 1, Integer.MAX_VALUE))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("합계가 절대 상한을 넘으면 INVALID_AMOUNT")
    void replaceDrafts_overMaxTotal_rejected() {
        stubChargeableReservationForUpdate();
        given(paymentRepository.existsByReservationId(RESERVATION_ID)).willReturn(false);

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("진찰료", 2, MAX_AMOUNT / 2 + 1))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
    }

    @Test
    @DisplayName("진료 완료 전이면 RESERVATION_NOT_CHARGEABLE — 작성 창은 진료 완료 후부터다")
    void replaceDrafts_beforeTreatmentCompleted_rejected() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForChargeForUpdate(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, false)));

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("진찰료", 1, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("타 병원 스태프는 초안을 작성할 수 없다(403)")
    void replaceDrafts_otherHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID + 1));
        given(reservationLookupPort.findForChargeForUpdate(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true)));

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("진찰료", 1, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
        verify(paymentItemRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("소속 병원이 없는 계정은 예약을 읽기 전에 403으로 막힌다")
    void replaceDrafts_noHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(RESERVATION_ID, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("진찰료", 1, 20_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
        verify(reservationLookupPort, never()).findForChargeForUpdate(anyLong());
    }

    @Test
    @DisplayName("초안 조회는 아직 청구되지 않은 항목만 반환한다")
    void getDrafts_returnsOnlyDrafts() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true)));
        given(paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(RESERVATION_ID))
                .willReturn(List.of(PaymentItem.draft(RESERVATION_ID, "진찰료", 1, 20_000, 20_000)));

        PaymentItemDraftResponse draftsResponse = paymentItemDraftService.getDrafts(RESERVATION_ID, STAFF_MEMBER_ID);

        assertThat(draftsResponse.items()).extracting(PaymentItemResponse::name).containsExactly("진찰료");
    }

    @Test
    @DisplayName("타 병원 스태프는 초안을 조회할 수 없다(403)")
    void getDrafts_otherHospital_forbidden() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID + 1));
        given(reservationLookupPort.findForCharge(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true)));

        assertThatThrownBy(() -> paymentItemDraftService.getDrafts(RESERVATION_ID, STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.FORBIDDEN_HOSPITAL);
        verify(paymentItemRepository, never())
                .findByReservationIdAndPaymentIdIsNullOrderByIdAsc(anyLong());
    }

    private void stubChargeableReservationForUpdate() {
        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForChargeForUpdate(RESERVATION_ID)).willReturn(Optional.of(
                new ReservationChargeView(RESERVATION_ID, HOSPITAL_ID, GUARDIAN_ID, PAYMENT_METHOD_ID, true)));
    }

    @SuppressWarnings("unchecked")
    private List<PaymentItem> savedItems() {
        ArgumentCaptor<List<PaymentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(paymentItemRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
