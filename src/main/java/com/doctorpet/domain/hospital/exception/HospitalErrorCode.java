package com.doctorpet.domain.hospital.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum HospitalErrorCode implements ErrorCode {

    HOSPITAL_NOT_FOUND(HttpStatus.NOT_FOUND, "HOSPITAL_001", "병원 정보를 찾을 수 없습니다."),
    HOSPITAL_DETAIL_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "HOSPITAL_002", "병원 상세정보를 불러오는 중 오류가 발생했습니다."),
    NOT_OWN_HOSPITAL(HttpStatus.FORBIDDEN, "HOSPITAL_003", "해당 병원에 접근할 권한이 없습니다."),
    OPERATING_SCHEDULE_NOT_FOUND(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "HOSPITAL_004",
            "병원 진료시간을 불러오는 중 오류가 발생했습니다."
    ),
    INVALID_OPERATING_HOURS_EFFECTIVE_DATE(
            HttpStatus.BAD_REQUEST,
            "HOSPITAL_005",
            "진료시간은 요청일 다음 날부터 적용할 수 있습니다."
    ),
    INVALID_OPERATING_HOURS(
            HttpStatus.BAD_REQUEST,
            "HOSPITAL_006",
            "요일별 진료시간이 올바르지 않습니다."
    ),
    INVALID_TEMPORARY_CLOSURE_DATE(
            HttpStatus.BAD_REQUEST,
            "HOSPITAL_007",
            "임시 휴무는 요청일 다음 날부터 등록할 수 있습니다."
    ),
    TEMPORARY_CLOSURE_HAS_RESERVATION(
            HttpStatus.CONFLICT,
            "HOSPITAL_008",
            "예약이 있는 영업일은 임시 휴무로 등록할 수 없습니다."
    ),
    TEMPORARY_CLOSURE_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "HOSPITAL_009",
            "이미 임시 휴무로 등록된 영업일입니다."
    ),
    TEMPORARY_CLOSURE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "HOSPITAL_010",
            "임시 휴무 정보를 찾을 수 없습니다."
    ),
    TEMPORARY_CLOSURE_CANCEL_DEADLINE_PASSED(
            HttpStatus.BAD_REQUEST,
            "HOSPITAL_011",
            "임시 휴무는 휴무 영업일 전날까지만 취소할 수 있습니다."
    ),
    DUPLICATE_CAPABILITY(
            HttpStatus.BAD_REQUEST,
            "HOSPITAL_012",
            "진료 역량을 중복해서 입력할 수 없습니다."
    ),
    CLOSED_HOSPITAL_CAPABILITY_UPDATE_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "HOSPITAL_013",
            "폐업한 병원의 진료 역량은 수정할 수 없습니다."
    ),
    HOSPITAL_RESERVATION_NOT_AVAILABLE(
            HttpStatus.CONFLICT,
            "HOSPITAL_014",
            "현재 병원에는 예약을 요청할 수 없습니다."
    ),
    HOSPITAL_RESERVATION_APPROVAL_NOT_AVAILABLE(
            HttpStatus.CONFLICT,
            "HOSPITAL_015",
            "현재 병원은 예약을 승인할 수 없습니다."
    ),
    OPERATING_SCHEDULE_CONFLICT(
            HttpStatus.CONFLICT,
            "HOSPITAL_016",
            "진료시간이 다른 변경으로 갱신되었습니다. 새로고침 후 다시 시도해 주세요."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
