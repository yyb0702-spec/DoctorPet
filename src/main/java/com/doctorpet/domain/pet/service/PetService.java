package com.doctorpet.domain.pet.service;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.exception.PetErrorCode;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
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

    /*
      내 반려동물 목록 조회. SA §8-2 — 인증된 회원 소유 프로필만 대상이며(Soft Delete된
      건은 PetProfile의 @SQLRestriction으로 이미 제외된다), 요청 파라미터가 아니라 인증
      주체(memberId) 기준으로 조회 범위를 정한다.
     */
    @Transactional(readOnly = true)
    public List<PetResponse> getMyPets(Long memberId) {
        return petProfileRepository.findAllByMemberIdOrderByIdAsc(memberId).stream()
                .map(PetResponse::from)
                .toList();
    }

    /*
      반려동물 상세 조회. SA §8-2 — "보호자(본인)"만 조회 가능하다.
      존재 자체가 없는 petId와 "존재하지만 다른 회원 소유"인 petId를 구분해서 응답한다
      (전자는 PET_NOT_FOUND 404, 후자는 CommonErrorCode.FORBIDDEN 403 — SA §6-2 "권한
      없음 403" 정책).
     */
    @Transactional(readOnly = true)
    public PetResponse getPet(Long memberId, Long petId) {
        PetProfile petProfile = petProfileRepository.findById(petId)
                .orElseThrow(() -> new ServiceException(PetErrorCode.PET_NOT_FOUND));

        if (!petProfile.getMemberId().equals(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }

        return PetResponse.from(petProfile);
    }

    /*
      반려동물 프로필 수정. SA §8-2 — "보호자(본인)"만 수정 가능하다. 존재 여부·소유권
      판단은 getPet()과 동일한 원칙을 따른다(PET_NOT_FOUND 404 / FORBIDDEN 403 구분).
      영속 상태(managed) 엔티티를 그대로 수정해 트랜잭션 커밋 시 더티 체킹으로 반영한다.
     */
    @Transactional
    public PetResponse update(Long memberId, Long petId, PetUpdateRequest request) {
        PetProfile petProfile = petProfileRepository.findById(petId)
                .orElseThrow(() -> new ServiceException(PetErrorCode.PET_NOT_FOUND));

        if (!petProfile.getMemberId().equals(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }

        petProfile.update(
                request.name(),
                request.species(),
                request.age(),
                request.weight(),
                request.neutered()
        );

        return PetResponse.from(petProfile);
    }
}
