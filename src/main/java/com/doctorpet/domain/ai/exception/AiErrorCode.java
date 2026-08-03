package com.doctorpet.domain.ai.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements ErrorCode {

    RATE_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "AI_001",
            "AI 상담 요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
