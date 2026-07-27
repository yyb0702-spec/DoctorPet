package com.doctorpet.domain.reservation.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SlotErrorCode implements ErrorCode {

    SLOT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SLOT_001",
            "예약 슬롯을 찾을 수 없습니다."
    ),

    ALREADY_RESERVED(
            HttpStatus.CONFLICT,
            "SLOT_002",
            "이미 예약된 시간대입니다."
    ),

    INVALID_STATUS(
            HttpStatus.CONFLICT,
            "SLOT_003",
            "현재 슬롯 상태에서는 해당 작업을 수행할 수 없습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
