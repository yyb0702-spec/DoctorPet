package com.doctorpet.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_002", "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_003", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_004", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_005", "서버 내부 오류가 발생했습니다."),
    // DB unique 제약 위반의 공통 처리용. 어떤 필드가 중복인지는 서비스 계층의 사전 체크(예: MemberErrorCode.DUPLICATE_EMAIL)가
    // 먼저 구체적으로 안내하고, 이 코드는 그 사전 체크를 우회한 경쟁 상태 등 예외적인 경우의 최종 방어선이다.
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "COMMON_006", "이미 사용 중인 값입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
