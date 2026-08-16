package com.doctorpet.domain.payment.support;

import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.service.PaymentItemCommand;
import com.doctorpet.domain.payment.service.PaymentItemDraftToken;
import java.util.List;

/**
 * 청구 항목 테스트 지원(SA §9-4 청구 항목). 청구가 초안 항목을 전제로 하게 되면서, 항목 자체가 관심사가 아닌
 * 테스트(재시도 분기·정산·환불·동시성)도 청구 전에 초안을 깔아야 한다. 그 준비와 정리를 여기 모아 둔다.
 * 항목 계약 자체는 PaymentItemDraftServiceTest·PaymentChargeServiceTest·PaymentItemChargeIntegrationTest가
 * 직접 항목을 구성해 검증한다.
 */
public final class PaymentItemTestSupport {

    private PaymentItemTestSupport() {
    }

    /** 합계가 {@code amount}가 되는 단일 항목 커맨드. 수량 1·단가 = amount라 항목 합계 = 총액이 자명하다. */
    public static List<PaymentItemCommand> singleItem(int amount) {
        return List.of(new PaymentItemCommand("진료비", 1, amount));
    }

    /**
     * 청구 전제인 초안 항목을 실제로 저장한다. 청구 선기록은 예약의 초안 항목 합계로 총액을 산출하므로,
     * 초안이 없으면 {@code PAYMENT_ITEM_REQUIRED}로 거부된다(SA §9-4).
     */
    public static void persistDraft(
            PaymentItemRepository paymentItemRepository, Long reservationId, int amount
    ) {
        paymentItemRepository.saveAndFlush(
                PaymentItem.draft(reservationId, "진료비", 1, amount, amount));
    }

    /**
     * 저장된 초안의 낙관적 검증 토큰. 청구는 저장 응답이 준 토큰을 되받아 잠금 아래에서 대조하므로
     * (SA §9-4 "초안 교체 경합"), 항목이 관심사가 아닌 테스트도 현재 초안의 토큰으로 청구해야 한다.
     */
    public static String draftToken(
            PaymentItemRepository paymentItemRepository, Long reservationId
    ) {
        return PaymentItemDraftToken.of(
                paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId));
    }

    /**
     * 통합 테스트가 남긴 항목 행을 정리한다. payments↔payment_items는 DB 외래 키가 아니라 FK 값(Long)
     * 참조라(SA §4) 결제만 지우면 항목이 남아, 공유 MySQL에 결제 없는 항목 행이 쌓인다.
     * 청구까지 갔으면 항목이 스탬프됐고 청구 전이면 초안으로 남아 있으므로 두 경우를 모두 지운다
     * ({@code paymentId}는 청구가 없었으면 null).
     */
    public static void deleteItems(
            PaymentItemRepository paymentItemRepository, Long reservationId, Long paymentId
    ) {
        paymentItemRepository.deleteAll(
                paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId));
        if (paymentId != null) {
            paymentItemRepository.deleteAll(paymentItemRepository.findByPaymentIdOrderByIdAsc(paymentId));
        }
    }
}
