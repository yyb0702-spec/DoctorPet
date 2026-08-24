package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/*
  결제 실패 셀프 복구(다시 결제) 요청(고도화 3.3, SA §9-4). 보호자가 OFFLINE_REQUIRED 결제를 다시 결제할 때,
  새로 결제할 ACTIVE 결제수단을 지정한다.

  결제수단 재지정 API(PATCH /api/reservations/{id}/payment-method, 3.2)는 결제 선기록이 이미 있으면 거부하므로,
  OFFLINE_REQUIRED 상태에서는 그 경로를 쓸 수 없다. 그래서 셀프 복구 요청이 결제수단을 직접 지정한다. 금액·항목은
  받지 않는다 — 원 결제의 총액·항목을 그대로 승계하고 클라이언트가 바꿀 수 없다(SA §9-4).
 */
public record PaymentRechargeRequest(
        @NotNull(message = "결제수단을 지정해 주세요.")
        @Positive(message = "결제수단 식별자가 올바르지 않습니다.")
        Long paymentMethodId
) {
}
