package com.doctorpet.domain.hospital.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum HospitalErrorCode implements ErrorCode {

    HOSPITAL_NOT_FOUND(HttpStatus.NOT_FOUND, "HOSPITAL_001", "병원 정보를 찾을 수 없습니다."),
    HOSPITAL_DETAIL_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "HOSPITAL_002", "병원 상세정보를 불러오는 중 오류가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
