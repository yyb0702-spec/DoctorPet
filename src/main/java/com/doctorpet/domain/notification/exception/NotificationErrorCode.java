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
    ),

    // SSE 구독 티켓이 없거나 만료·이미 사용됨(1회성). EventSource는 헤더 인증을 못 하므로 단기 티켓으로 식별한다(SA §9-8).
    SSE_TICKET_INVALID(
            HttpStatus.UNAUTHORIZED,
            "NOTIFICATION_003",
            "유효하지 않거나 만료된 실시간 알림 구독 티켓입니다."
    ),

    // 회원당 동시 SSE 연결 상한 초과(리소스 소진 방지). 죽은 연결은 heartbeat 주기에 정리되므로 잠시 후 재시도하면 된다.
    SSE_TOO_MANY_CONNECTIONS(
            HttpStatus.TOO_MANY_REQUESTS,
            "NOTIFICATION_004",
            "실시간 알림 동시 연결 수가 한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
    ),

    // 병원 스태프 principal인데 소속 병원을 확인할 수 없어(role 불일치·hospitalId 없음) 수신자를 특정하지 못함(고도화 3.10).
    NOTIFICATION_RECIPIENT_UNRESOLVED(
            HttpStatus.FORBIDDEN,
            "NOTIFICATION_005",
            "알림 수신자를 확인할 수 없습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
