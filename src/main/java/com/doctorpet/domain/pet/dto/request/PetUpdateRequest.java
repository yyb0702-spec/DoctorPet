package com.doctorpet.domain.pet.dto.request;

import com.doctorpet.domain.pet.entity.PetSpecies;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 반려동물 프로필 수정 요청. SA §8-2: {@code PATCH /api/pets/{petId}}.
 *
 * 부분 수정(Merge Patch)이다 — 필드를 생략하면(JSON에 없거나 값이 {@code null}이면) 기존 값을
 * 그대로 유지한다({@link com.doctorpet.domain.pet.entity.PetProfile#update}가 병합한다).
 * PetCreateRequest와 달리 모든 필드가 선택값이라 {@code @NotBlank}/{@code @NotNull}을 걸지 않고,
 * 값이 존재할 때만 적용되는 표준 제약만 둔다 — Bean Validation 표준 제약은 기본적으로 null을
 * 유효한 값으로 취급하므로 별도 처리 없이도 "생략하면 통과, 값이 있으면 검증" 의미를 만족한다.
 *
 * {@code name}은 {@code @Pattern(".*\S.*")}로 공백 문자만 있는 값(" ")과 빈 문자열("") 모두
 * 거부한다 — null은 여전히 통과한다(생략 허용). 등록(PetCreateRequest)의 {@code @NotBlank}와
 * 동일한 "공백만 있는 이름은 안 된다" 불변조건을, null 허용이 필요한 부분 수정에서도 유지한다.
 * memberId(소유자)는 요청 body에 두지 않는다.
 */
public record PetUpdateRequest(

        @Pattern(regexp = ".*\\S.*", message = "이름은 공백만으로 이루어지거나 비어 있을 수 없습니다.")
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

        Boolean neutered,

        // POST /api/pets/{petId}/image/upload-url로 발급받은 presigned URL에 업로드를 마친 뒤,
        // 그 결과 fileUrl을 이 필드로 저장(확정)한다. 형식 검증은 하지 않는다 — 값은 서버가 같은
        // 요청 흐름에서 발급한 URL을 그대로 돌려받는 용도라 임의 문자열을 걸러야 할 신뢰 경계가
        // 아니다(길이 제한만 DB 컬럼(VARCHAR(2048))과 맞춘다).
        @Size(max = 2048, message = "이미지 URL은 2048자를 초과할 수 없습니다.")
        String imageUrl
) {
}
