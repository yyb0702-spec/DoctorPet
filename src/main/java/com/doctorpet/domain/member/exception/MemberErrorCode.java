package com.doctorpet.domain.member.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "MEMBER_001", "이미 사용 중인 이메일입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
