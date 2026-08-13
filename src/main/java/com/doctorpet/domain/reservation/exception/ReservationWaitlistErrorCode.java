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
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
