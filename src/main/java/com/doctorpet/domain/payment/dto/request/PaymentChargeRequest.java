package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/*
  진료비 청구 요청(#34, 항목화는 고도화 결제 3.1). 병원 스태프가 진료 완료 후 청구 항목을 입력한다.
  - 총액(amount)은 요청으로 받지 않는다. 서버가 항목 합계로 Payment.amount를 계산한다 — 클라이언트가 보낸
    총액을 믿으면 항목 합계와 어긋난 금액이 승인될 수 있다(SA §9-4 "클라이언트 결과를 믿지 않는다"와 같은 원칙).
  - 결제수단·보호자·병원은 요청이 아니라 예약(확정된 결제수단)과 인증 주체에서 얻는다(신뢰 금지, SA §9-4·보안).
  - 항목은 최소 1개여야 한다(@NotEmpty → VALIDATION_FAILED 400). 합계 0 이하·상한 초과는 Service가
    INVALID_AMOUNT로 처리한다.
 */
public record PaymentChargeRequest(
        @NotEmpty(message = "청구 항목을 1개 이상 입력해주세요.")
        @Valid
        List<PaymentItemRequest> items
) {
}
