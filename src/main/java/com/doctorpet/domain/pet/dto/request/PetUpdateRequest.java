package com.doctorpet.domain.pet.dto.request;

import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.validation.NullOrNotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 반려동물 프로필 수정 요청. SA §8-2: {@code PATCH /api/pets/{petId}}.
 *
 * 부분 수정(Merge Patch)이다 — 필드를 생략하면(JSON에 없거나 값이 {@code null}이면) 기존 값을
 * 그대로 유지한다({@link com.doctorpet.domain.pet.entity.PetProfile#update}가 병합한다).
 * PetCreateRequest와 달리 모든 필드가 선택值이라 {@code @NotBlank}/{@code @NotNull}을 걸지 않고,
 * 대신 값이 존재할 때만 적용되는 제약(null이면 항상 통과)만 둔다 — Bean Validation 표준 제약은
 * 기본적으로 null을 유효한 값으로 취급하므로 {@code @Size}/{@code @PositiveOrZero}/
 * {@code @DecimalMin}/{@code @Digits}는 별도 처리 없이도 이 의미를 만족한다. 다만 문자열의
 * "생략은 허용하되 빈 문자열은 거부"는 표준 제약으로 표현할 수 없어 {@link NullOrNotBlank}를 쓴다.
 * memberId(소유자)는 요청 body에 두지 않는다.
 */
public record PetUpdateRequest(

        @NullOrNotBlank(message = "이름은 비어 있을 수 없습니다.")
        @Size(max = 255, message = "이름은 255자를 초과할 수 없습니다.")
        String name,

        PetSpecies species,

        @PositiveOrZero(message = "나이는 0 이상이어야 합니다.")
        Integer age,

        @DecimalMin(value = "0.0", inclusive = false, message = "체중은 0보다 커야 합니다.")
        // DB 컬럼(DECIMAL(5,2))과 정확히 맞춘 정밀도. 넘는 값은 DB에서 조용히 반올림되거나
        // 저장 시점에 예상치 못한 500으로 이어질 수 있어 API 레벨에서 먼저 거부한다.
        @Digits(integer = 3, fraction = 2, message = "체중은 정수 3자리, 소수 2자리 이내여야 합니다.")
        BigDecimal weight,

        Boolean neutered
) {
}
