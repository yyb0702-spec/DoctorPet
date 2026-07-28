package com.doctorpet.domain.pet.dto.request;

import com.doctorpet.domain.pet.entity.PetSpecies;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 반려동물 프로필 수정 요청. SA §8-2: {@code PATCH /api/pets/{petId}}.
 * PetCreateRequest와 동일한 5개 필드를 전체 교체하는 방식이다(부분 수정 아님) — 등록 때와
 * 같은 검증 로직을 그대로 재사용한다. memberId(소유자)는 요청 body에 두지 않는다.
 */
public record PetUpdateRequest(

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 255, message = "이름은 255자를 초과할 수 없습니다.")
        String name,

        @NotNull(message = "동물 종은 필수입니다.")
        PetSpecies species,

        @NotNull(message = "나이는 필수입니다.")
        @PositiveOrZero(message = "나이는 0 이상이어야 합니다.")
        Integer age,

        @NotNull(message = "체중은 필수입니다.")
        @DecimalMin(value = "0.0", inclusive = false, message = "체중은 0보다 커야 합니다.")
        BigDecimal weight,

        @NotNull(message = "중성화 여부는 필수입니다.")
        Boolean neutered
) {
}
