package com.doctorpet.domain.notification.exception;

// 알림 도메인 에러코드(NOTIFICATION_{3자리}). 글로벌 통합 enum을 쓰지 않고 도메인별로 분리한다(AGENTS 확정 결정).

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "NOTIFICATION_001",
            "알림을 찾을 수 없습니다."
    ),

    NOTIFICATION_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "NOTIFICATION_002",
            "본인의 알림만 접근할 수 있습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
