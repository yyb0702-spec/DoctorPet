package com.doctorpet.domain.pet.dto.request;

import com.doctorpet.domain.pet.entity.PetSpecies;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 반려동물 프로필 등록 요청. SA §8-2: {@code POST /api/pets { name, species, age, weight, neutered }}.
 * memberId는 요청 body에 두지 않는다 — 인증 주체(@AuthenticationPrincipal)에서 얻는다.
 *
 * name에 상한을 둔 이유는 SignupRequest.nickname과 같다 — DB 컬럼 길이(기본 varchar(255))를
 * 넘는 값이 검증을 통과해 DataIntegrityViolationException(500)으로 이어지는 것을 막기 위함이다.
 * species는 Java enum(PetSpecies)으로 받아, 화이트리스트 밖의 값은 Jackson
 * 역직렬화 단계에서 자체적으로 거부되어 GlobalExceptionHandler가 400으로 응답한다.
 */
public record PetCreateRequest(

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
        // DB 컬럼(DECIMAL(5,2))과 정확히 맞춘 정밀도. 넘는 값은 DB에서 조용히 반올림되거나
        // 저장 시점에 예상치 못한 500으로 이어질 수 있어 API 레벨에서 먼저 거부한다.
        @Digits(integer = 3, fraction = 2, message = "체중은 정수 3자리, 소수 2자리 이내여야 합니다.")
        BigDecimal weight,

        @NotNull(message = "중성화 여부는 필수입니다.")
        Boolean neutered
) {
}
