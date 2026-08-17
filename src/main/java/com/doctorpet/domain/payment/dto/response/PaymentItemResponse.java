package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.PaymentItem;
import java.util.List;

/*
  청구 항목 1건(SA §4 payment_items). 초안 조회·저장 응답과 영수증 항목 목록이 같은 형태를 쓴다 —
  같은 행의 같은 필드를 보여주므로 형태를 갈라 두면 클라이언트가 두 번 매핑해야 한다.
  unitPrice·amount는 할인·조정 항목에서 음수일 수 있다 — 클라이언트는 음수 표시를 전제해야 한다.
  reservation_id·payment_id는 응답에 담지 않는다(경로·상위 응답이 이미 문맥을 준다).
 */
public record PaymentItemResponse(
        String name,
        int quantity,
        int unitPrice,
        int amount
) {

    public static PaymentItemResponse from(PaymentItem item) {
        return new PaymentItemResponse(
                item.getName(), item.getQuantity(), item.getUnitPrice(), item.getAmount());
    }

    public static List<PaymentItemResponse> from(List<PaymentItem> items) {
        return items.stream().map(PaymentItemResponse::from).toList();
    }
}
