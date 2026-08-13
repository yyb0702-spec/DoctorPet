package com.doctorpet.domain.payment.support;

import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.service.PaymentItemCommand;
import java.util.List;

/**
 * 청구 항목 테스트 지원(고도화 결제 3.1). 청구 계약이 총액 단일 값에서 항목 목록으로 바뀌면서,
 * 항목 자체가 관심사가 아닌 테스트(재시도 분기·정산·환불·동시성)가 매번 항목 리터럴을 늘어놓거나
 * 항목 정리 코드를 중복하지 않도록 모아 둔다.
 * 항목 검증 자체는 PaymentChargeServiceTest·PaymentItemChargeIntegrationTest가 직접 항목을 구성해 검증한다.
 */
public final class PaymentItemTestSupport {

    private PaymentItemTestSupport() {
    }

    /** 합계가 {@code amount}가 되는 단일 항목. 수량 1·단가 = amount라 항목 합계 = 총액이 자명하다. */
    public static List<PaymentItemCommand> singleItem(int amount) {
        return List.of(new PaymentItemCommand("진료비", 1, amount));
    }

    /**
     * 결제를 지우는 통합 테스트가 항목 행까지 함께 정리하도록 한다. payments↔payment_items는 DB 외래 키가
     * 아니라 FK 값(Long) 참조라(SA §4) 결제만 지우면 항목이 남아, 공유 MySQL에 결제 없는 항목 행이 쌓인다.
     * 항목이 결제보다 먼저 사라져야 하므로 결제를 지우기 전에 호출한다.
     */
    public static void deleteItemsOf(PaymentItemRepository paymentItemRepository, Long paymentId) {
        paymentItemRepository.deleteAll(paymentItemRepository.findByPaymentIdOrderByIdAsc(paymentId));
    }
}
