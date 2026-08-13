package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/*
  청구 항목 1건(SA §9-4 청구 항목). 병원 스태프가 진료 완료 후 입력한다.
  - 항목 금액(line amount)은 요청으로 받지 않는다 — 서버가 quantity × unitPrice로 계산한다(요청 금액 신뢰 금지).
  - quantity는 양수만 허용한다(@Positive → VALIDATION_FAILED 400).
  - unitPrice는 할인·조정 항목(예: -5000원 쿠폰)을 위해 음수를 허용하므로 부호 제약을 두지 않는다.
    최종 총액이 0 초과·절대 상한 이하인지는 PaymentAmountPolicy가 INVALID_AMOUNT로 검증한다.
 */
public record PaymentItemRequest(
        @NotBlank(message = "항목명을 입력해주세요.")
        @Size(max = 100, message = "항목명은 100자 이내여야 합니다.")
        String name,

        @NotNull(message = "항목 수량을 입력해주세요.")
        @Positive(message = "항목 수량은 0보다 커야 합니다.")
        Integer quantity,

        @NotNull(message = "항목 단가를 입력해주세요.")
        Integer unitPrice
) {
}
