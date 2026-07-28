package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
  결제수단 등록 요청. 카드번호·CVC는 받지 않는다 — 클라이언트가 카드 인증으로 발급받은 빌링키만 전달한다.
 */
public record PaymentMethodRegisterRequest(

        // 실제 PortOne 빌링키는 수십~백 자 수준이다. 상한을 둬 비정상 길이 입력이 외부 호출·암호화를 거쳐
        // billing_key_enc(VARCHAR(1000))를 넘겨 DB 500으로 이어지는 것을 Validation 400 단계에서 차단한다.
        // 255자 원문은 암호화(IV·태그+Base64+접두) 후에도 1000자 컬럼에 넉넉히 들어간다.
        @NotBlank(message = "빌링키는 필수입니다.")
        @Size(max = 255, message = "빌링키 길이가 허용 범위를 초과했습니다.")
        String billingKey
) {
}
