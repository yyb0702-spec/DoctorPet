package com.doctorpet.domain.pet.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PetErrorCode implements ErrorCode {

    // petId 자체가 존재하지 않거나(Soft Delete 포함) 이미 삭제된 경우.
    // 다른 회원 소유인 경우는 이 코드가 아니라 CommonErrorCode.FORBIDDEN(403)으로 구분한다
    // (SA §6-2 "권한 없음 403" 정책에 따라 존재 여부와 소유권 여부를 분리해서 응답한다).
    PET_NOT_FOUND(HttpStatus.NOT_FOUND, "PET_001", "존재하지 않는 반려동물입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
