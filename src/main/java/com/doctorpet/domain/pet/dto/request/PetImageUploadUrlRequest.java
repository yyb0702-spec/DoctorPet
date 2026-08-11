package com.doctorpet.domain.pet.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 반려동물 프로필 이미지 업로드 URL 발급 요청. {@code POST /api/pets/{petId}/image/upload-url}.
 *
 * <p>{@code contentType}은 jpeg·png·webp만 허용한다(정책 — 이미지 형식 제한). 허용되지 않는 값은
 * {@code @Pattern} 위반으로 {@code COMMON_001}(400)이 되며, 별도 PetErrorCode를 두지 않는다
 * (SignupRequest의 phone 정규식 검증과 동일한 관례).
 */
public record PetImageUploadUrlRequest(

        @NotBlank(message = "contentType은 필수입니다.")
        @Pattern(
                regexp = "^image/(jpeg|png|webp)$",
                message = "contentType은 image/jpeg, image/png, image/webp만 허용합니다."
        )
        String contentType
) {
}
