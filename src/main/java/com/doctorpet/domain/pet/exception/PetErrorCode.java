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
    PET_NOT_FOUND(HttpStatus.NOT_FOUND, "PET_001", "존재하지 않는 반려동물입니다."),

    // PATCH로 확정 저장하려는 imageUrl이 이 스토리지가 발급 가능한 형식(스킴·호스트)이 아니거나,
    // 요청한 petId의 key 네임스페이스(pets/{petId}/)로 시작하지 않는 경우. 업로드 절차(presigned
    // URL 발급)를 거치지 않은 임의 외부 URL이나 다른 반려동물의 오브젝트 URL을 그대로 저장하는
    // 것을 막기 위한 최소 방어선이다(리뷰 지적 대응, ImageStorageGateway#isManagedFileUrl 참고).
    INVALID_IMAGE_URL(HttpStatus.BAD_REQUEST, "PET_002", "허용되지 않은 이미지 URL입니다. 발급받은 업로드 URL로 업로드한 이미지만 저장할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
