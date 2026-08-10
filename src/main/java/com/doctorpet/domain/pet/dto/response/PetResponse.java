package com.doctorpet.domain.pet.dto.response;

import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import java.math.BigDecimal;

/**
 * 반려동물 프로필 응답. 등록(POST)뿐 아니라 이후 조회(GET)에서도 재사용할 수 있도록
 * 필드 전체를 담는다.
 */
public record PetResponse(
        Long petId,
        String name,
        PetSpecies species,
        Integer age,
        BigDecimal weight,
        Boolean neutered,
        String imageUrl
) {

    public static PetResponse from(PetProfile petProfile) {
        return new PetResponse(
                petProfile.getId(),
                petProfile.getName(),
                petProfile.getSpecies(),
                petProfile.getAge(),
                petProfile.getWeight(),
                petProfile.getNeutered(),
                petProfile.getImageUrl()
        );
    }
}
