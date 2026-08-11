package com.doctorpet.domain.pet.dto.response;

/**
 * 반려동물 프로필 이미지 업로드 URL 발급 응답.
 *
 * <p>클라이언트는 {@code uploadUrl}로 파일 바이트를 직접 PUT 업로드한 뒤, {@code imageUrl}을
 * {@code PATCH /api/pets/{petId}} 요청의 {@code imageUrl}로 보내 저장을 확정한다(2단계 흐름 —
 * presigned URL 방식, 서버는 파일 바이트를 중계하지 않는다).
 */
public record PetImageUploadUrlResponse(
        String uploadUrl,
        String imageUrl,
        int expiresInSeconds
) {
}
