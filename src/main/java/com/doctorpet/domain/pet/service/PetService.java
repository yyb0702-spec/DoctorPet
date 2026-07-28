package com.doctorpet.domain.pet.service;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PetService {

    private final PetProfileRepository petProfileRepository;

    /*
      반려동물 프로필 등록. SA §8-2, A 도메인 결정 #4.
      보호자 1인당 등록 개수 제한은 없다 — 다반려동물 가구를 고려한 결정.
     */
    @Transactional
    public PetResponse register(Long memberId, PetCreateRequest request) {
        PetProfile petProfile = PetProfile.create(
                memberId,
                request.name(),
                request.species(),
                request.age(),
                request.weight(),
                request.neutered()
        );

        PetProfile saved = petProfileRepository.save(petProfile);

        return PetResponse.from(saved);
    }
}
