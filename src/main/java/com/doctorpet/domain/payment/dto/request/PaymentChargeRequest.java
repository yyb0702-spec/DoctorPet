package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/*
  진료비 청구 요청(#34). 병원 스태프가 진료 완료 후 최종 금액만 입력한다.
  - 결제수단·보호자·병원은 요청이 아니라 예약(확정된 결제수단)과 인증 주체에서 얻는다(신뢰 금지, SA §9-4·보안).
  - 항목(line items)은 저장하지 않는다(MVP에 소비처 없음). 절대 상한 검증은 Service에서 설정값으로 수행한다.
  - @Positive는 0·음수를 400(VALIDATION_FAILED)으로 거른다. 상한 초과는 Service가 INVALID_AMOUNT로 처리한다.
 */
public record PaymentChargeRequest(
        @NotNull(message = "청구 금액을 입력해주세요.")
        @Positive(message = "청구 금액은 0보다 커야 합니다.")
        Integer amount
) {
}
