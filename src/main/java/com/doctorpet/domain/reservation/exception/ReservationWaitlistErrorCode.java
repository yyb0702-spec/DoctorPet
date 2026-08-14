package com.doctorpet.domain.reservation.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReservationWaitlistErrorCode implements ErrorCode {

    SLOT_NOT_RESERVED(
            HttpStatus.CONFLICT,
            "WAITLIST_001",
            "마감된 예약 슬롯에만 대기열을 등록할 수 있습니다."
    ),

    ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "WAITLIST_002",
            "이미 해당 예약 슬롯의 대기열에 등록했습니다."
    ),

    WAITLIST_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "WAITLIST_003",
            "예약 대기열을 찾을 수 없습니다."
    ),

    OFFER_NOT_ACTIVE(
            HttpStatus.CONFLICT,
            "WAITLIST_004",
            "현재 응답할 수 있는 승급 제안이 없습니다."
    ),

    OFFER_EXPIRED(
            HttpStatus.CONFLICT,
            "WAITLIST_005",
            "승급 제안의 응답 시간이 만료되었습니다."
    ),

    CANCELLATION_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "WAITLIST_006",
            "WAITING 상태의 예약 대기열만 취소할 수 있습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
