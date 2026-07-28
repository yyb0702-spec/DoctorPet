package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;

/*
  결제수단 등록 요청. 카드번호·CVC는 받지 않는다 — 클라이언트가 카드 인증으로 발급받은 빌링키만 전달한다.
 */
public record PaymentMethodRegisterRequest(

        @NotBlank(message = "빌링키는 필수입니다.")
        String billingKey
) {
}
