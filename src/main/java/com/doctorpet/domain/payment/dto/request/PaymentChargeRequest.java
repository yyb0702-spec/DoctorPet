package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;

/*
  진료비 청구 요청(SA §8-7·§9-4). **금액도 항목도 받지 않는다** — 총액은 서버가 저장된 초안 항목의
  합계로 산출한다(클라이언트 결과 신뢰 금지).

  받는 것은 초안 낙관적 검증 토큰 하나뿐이다. 초안 저장(PUT)과 청구(POST) 사이에는 예약 행 잠금이
  유지되지 않아, 그 틈에 다른 스태프가 초안을 교체하면 화면에서 확인한 금액이 아닌 남의 초안이 청구된다.
  조회·저장 응답이 준 토큰을 되보내면 서버가 잠금 아래에서 다시 계산해 대조하고, 다르면 409로 거부한다.
 */
public record PaymentChargeRequest(
        @NotBlank(message = "청구 항목 토큰이 필요합니다.")
        String draftToken
) {
}
