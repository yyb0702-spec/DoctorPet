package com.doctorpet.domain.reservation.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    PROFILE_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_001",
            "예약 전에 반려동물 프로필을 등록해야 합니다."
    ),

    PAYMENT_METHOD_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_002",
            "예약 전에 결제수단을 등록해야 합니다."
    ),

    LEAD_TIME_VIOLATION(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_003",
            "예약은 예약 시각 4시간 전까지만 요청할 수 있습니다."
    ),

    CANCEL_DEADLINE_PASSED(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_004",
            "예약 시각 2시간 전까지만 취소할 수 있습니다."
    ),

    INVALID_STATUS(
            HttpStatus.CONFLICT,
            "RESERVATION_005",
            "현재 예약 상태에서는 해당 작업을 수행할 수 없습니다."
    ),

    REJECT_REASON_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_006",
            "예약 거절 사유는 필수입니다."
    ),

    RESERVATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "RESERVATION_007",
            "예약 정보를 찾을 수 없습니다."
    ),

    INVALID_FILTER_STATUS(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_008",
            "지원하지 않는 예약 상태입니다."
    ),

    INVALID_DATE_RANGE(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_009",
            "조회 시작일은 종료일보다 늦을 수 없습니다."
    ),

    INVALID_SORT(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_010",
            "지원하지 않는 예약 정렬 조건입니다."
    ),

    APPROVAL_DEADLINE_PASSED(
            HttpStatus.CONFLICT,
            "RESERVATION_011",
            "예약 승인 가능 시간이 지났습니다."
    ),

    CHECK_IN_DEADLINE_PASSED(
            HttpStatus.CONFLICT,
            "RESERVATION_012",
            "예약 체크인 가능 시간이 지났습니다."
    ),

    NO_SHOW_TOO_EARLY(
            HttpStatus.CONFLICT,
            "RESERVATION_013",
            "예약 시작 전에는 노쇼를 확정할 수 없습니다."
    ),

    INVALID_NO_SHOW_REASON(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_014",
            "노쇼 확정 사유는 필수이며 255자 이하여야 합니다."
    ),

    INVALID_NO_SHOW_RESTORE_REASON(
            HttpStatus.BAD_REQUEST,
            "RESERVATION_015",
            "노쇼 정정 사유는 필수이며 255자 이하여야 합니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
