package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
  청구 금액 규칙(SA §9-4 금액 검증·청구 항목). 초안 저장과 청구 선기록이 **같은 규칙**을 써야 하므로 한곳에 둔다 —
  두 경로에 규칙을 복사하면 한쪽만 고쳐져 "저장은 되는데 청구가 안 되는 항목"이나 그 반대가 생긴다.

  계산 순서가 곧 안전장치다 — 곱셈·합산을 long으로 먼저 수행해 int overflow를 막고, 그 다음 int 범위와 절대
  상한을 검증한다. int로 먼저 곱하면 오버플로가 음수로 되감겨 상한 검증을 그대로 통과한다.

  항목 구조 문제(빈 목록·항목명 누락·수량 0 이하)는 INVALID_PAYMENT_ITEM, 금액 범위 문제는 INVALID_AMOUNT다.
  수량 양수의 1차 강제 지점은 요청 DTO `@Positive`(→ VALIDATION_FAILED 400)이고, 여기 검증은 서비스를 직접
  호출하는 경로까지 막는 2차 방어선이다(SA §9-4).
 */
@Component
public class PaymentAmountPolicy {

    private final int maxAmount;

    public PaymentAmountPolicy(
            // 진료비 절대 상한(원). 코드 상수가 아니라 설정값으로 둬 배포 없이 상향 가능하게 한다(SA §9-4).
            @Value("${payment.charge.max-amount:3000000}") int maxAmount
    ) {
        this.maxAmount = maxAmount;
    }

    /** 항목 금액(= 수량 × 단가). 곱셈을 long으로 해 int overflow를 막고, int 범위를 벗어나면 거부한다. */
    public int lineAmount(PaymentItemCommand item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            throw new ServiceException(PaymentErrorCode.INVALID_PAYMENT_ITEM);
        }
        if (item.quantity() <= 0) {
            throw new ServiceException(PaymentErrorCode.INVALID_PAYMENT_ITEM);
        }
        long lineAmount = (long) item.quantity() * item.unitPrice();
        if (lineAmount < Integer.MIN_VALUE || lineAmount > Integer.MAX_VALUE) {
            throw new ServiceException(PaymentErrorCode.INVALID_AMOUNT);
        }
        return (int) lineAmount;
    }

    /**
     * 초안으로 저장할 항목들의 합계. 저장 시점에 검증해, 어차피 청구할 수 없는 항목 구성이 초안으로 남지 않게 한다.
     * 청구 선기록도 {@link #totalOfItems}로 다시 검증하므로 이 검증이 최종 게이트는 아니다.
     */
    public int totalOfCommands(List<PaymentItemCommand> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException(PaymentErrorCode.INVALID_PAYMENT_ITEM);
        }
        long total = 0;
        for (PaymentItemCommand item : items) {
            total += lineAmount(item);
        }
        return validateTotal(total);
    }

    /**
     * 저장된 항목들의 합계. 청구 선기록이 예약 행 락을 잡고 초안을 재조회한 뒤 호출하는 **최종 게이트**다
     * (SA §9-4 — 총액은 서버가 항목 합계로 산출한다).
     */
    public int totalOfItems(List<PaymentItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_REQUIRED);
        }
        long total = 0;
        for (PaymentItem item : items) {
            total += item.getAmount();
        }
        return validateTotal(total);
    }

    /**
     * 최종 합계는 0 초과·절대 상한 이하다(SA §9-4). 각 항목이 int 범위 안이어도 누적 합계는 넘칠 수 있으므로
     * long으로 더한 뒤 여기서 한 번에 판정한다. 음수 할인 항목이 총액을 0 이하로 만드는 청구도 여기서 걸린다.
     */
    private int validateTotal(long total) {
        if (total <= 0 || total > maxAmount) {
            throw new ServiceException(PaymentErrorCode.INVALID_AMOUNT);
        }
        return (int) total;
    }
}
