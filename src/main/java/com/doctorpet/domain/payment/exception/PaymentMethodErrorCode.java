package com.doctorpet.domain.payment.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
  결제수단 도메인 에러코드(PAYMENT_METHOD_NNN). 진료비 청구(#34)의 결제 도메인 에러코드(PaymentErrorCode,
  PAYMENT_NNN)와 번호·의미가 겹치지 않도록 결제수단 전용 enum으로 분리한다(AGENTS 도메인별 enum 분리).
 */
@Getter
@RequiredArgsConstructor
public enum PaymentMethodErrorCode implements ErrorCode {

    // 게이트웨이가 빌링키를 유효하지 않다고 판정(위조·만료·미발급 등)한 경우.
    INVALID_BILLING_KEY(HttpStatus.BAD_REQUEST, "PAYMENT_METHOD_001", "유효하지 않은 빌링키입니다."),
    // 게이트웨이 호출 자체가 실패(네트워크·일시 장애 등)한 경우. 원문 빌링키는 메시지·로그에 남기지 않는다.
    BILLING_KEY_VERIFICATION_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_METHOD_002",
            "결제수단 인증에 실패했습니다. 잠시 후 다시 시도해주세요."),
    // 존재하지 않거나 본인 소유가 아닌 결제수단. 존재 여부 노출을 막기 위해 두 경우를 같은 404로 응답한다.
    PAYMENT_METHOD_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_METHOD_003", "결제수단을 찾을 수 없습니다."),
    PAYMENT_METHOD_NOT_ACTIVE(HttpStatus.CONFLICT, "PAYMENT_METHOD_004", "활성 상태의 결제수단만 지정할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
