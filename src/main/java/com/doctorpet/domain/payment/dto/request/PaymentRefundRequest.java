package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
  진료비 환불 요청(#37). 전액 환불만 지원하므로 금액은 받지 않는다 — 결제 레코드의 금액을 그대로 취소한다
  (클라이언트가 보낸 금액을 믿고 취소하면 과·소 환불이 가능해진다, 보안·SA §9-4).
  - 결제·보호자·병원은 요청이 아니라 결제 레코드와 인증 주체에서 얻는다.
  - 사유는 감사 기록용 필수값이다(누가·왜 되돌렸는지 없이 돈이 움직이면 추적이 불가능하다).
    보호자 알림에는 담지 않으며, 컬럼 길이(200)와 맞춰 @Size로 400에서 거른다.
 */
public record PaymentRefundRequest(
        @NotBlank(message = "환불 사유를 입력해주세요.")
        @Size(max = 200, message = "환불 사유는 200자 이내로 입력해주세요.")
        String reason
) {
}
