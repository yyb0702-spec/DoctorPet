package com.doctorpet.domain.chat.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {

    CHAT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "CHAT_001", "예약 채팅에 접근할 권한이 없습니다."),
    MESSAGE_SEND_NOT_ALLOWED(HttpStatus.CONFLICT, "CHAT_002", "현재 예약 상태에서는 메시지를 보낼 수 없습니다."),
    INVALID_AFTER_MESSAGE(HttpStatus.BAD_REQUEST, "CHAT_003", "커서 메시지가 이 예약 스레드에 속하지 않습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
