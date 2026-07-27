package com.doctorpet.domain.member.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "MEMBER_001", "이미 사용 중인 이메일입니다."),
    // 이메일 미존재/비밀번호 불일치를 구분하지 않고 같은 코드로 응답한다(계정 존재 여부 노출 방지).
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MEMBER_002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "MEMBER_003", "로그인 실패 횟수를 초과해 계정이 잠겼습니다. 30분 후 다시 시도하거나 비밀번호를 재설정하세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
