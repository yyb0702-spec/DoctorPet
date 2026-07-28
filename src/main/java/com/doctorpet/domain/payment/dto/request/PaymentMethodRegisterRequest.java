package com.doctorpet.domain.payment.dto.request;

import com.doctorpet.global.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;

/*
  결제수단 등록 요청. 카드번호·CVC는 받지 않는다 — 클라이언트가 카드 인증으로 발급받은 빌링키만 전달한다.
 */
public record PaymentMethodRegisterRequest(

        // 상한을 둬 비정상 길이 입력이 외부 호출·암호화를 거쳐 billing_key_enc(VARCHAR(1000))를 넘겨
        // DB 500으로 이어지는 것을 Validation 400 단계에서 차단한다. 암호화는 UTF-8 바이트 단위로 이뤄지므로
        // (문자 수 기준 @Size는 한글·이모지 입력에서 바이트가 배로 늘어 상한을 못 지킨다) 바이트 길이로 제한한다.
        // 512바이트면 v1: 접두 + Base64(IV 12 + 본문 512 + 태그 16) ≈ 723자로 1000자 컬럼에 여유 있게 들어가고,
        // 실제 PortOne 빌링키(짧은 ASCII 토큰)에는 충분히 넉넉하다.
        @NotBlank(message = "빌링키는 필수입니다.")
        @MaxUtf8Bytes(value = 512, message = "빌링키 길이가 허용 범위를 초과했습니다.")
        String billingKey
) {
}
