package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.PaymentItem;

/*
  영수증 항목 1건(고도화 결제 3.4). 청구 시점 스냅샷을 그대로 내려준다.
  unitPrice·amount는 할인·조정 항목에서 음수일 수 있다 — 클라이언트는 음수 표시를 전제해야 한다.
 */
public record PaymentReceiptItemResponse(
        String name,
        int quantity,
        int unitPrice,
        int amount
) {

    public static PaymentReceiptItemResponse from(PaymentItem item) {
        return new PaymentReceiptItemResponse(
                item.getName(), item.getQuantity(), item.getUnitPrice(), item.getAmount());
    }
}
