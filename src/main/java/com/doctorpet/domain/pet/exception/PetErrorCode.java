package com.doctorpet.domain.pet.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PetErrorCode implements ErrorCode {

    // petId 자체가 존재하지 않거나(Soft Delete 포함) 이미 삭제된 경우.
    // 다른 회원 소유인 경우는 이 코드가 아니라 CommonErrorCode.FORBIDDEN(403)으로 구분한다.
    // SA §6-1의 "미인증 401, 권한 없음 403"은 상태 코드 매핑 일반 규정일 뿐, 존재 여부와
    // 소유권 여부를 나눠서 응답하라는 내용은 SA에 없다 — 이 분리는 PR 리뷰에서 논의 후
    // 팀이 내린 결정이다(petId가 순차적(IDENTITY)이라 403 응답이 해당 id의 실존을
    // 간접적으로 드러내지만, 반려동물 프로필 존재 자체는 민감 정보가 아니라고 판단했다).
    PET_NOT_FOUND(HttpStatus.NOT_FOUND, "PET_001", "존재하지 않는 반려동물입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
