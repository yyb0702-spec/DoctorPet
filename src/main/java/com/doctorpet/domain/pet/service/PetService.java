package com.doctorpet.domain.pet.service;

import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.exception.PetErrorCode;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PetService {

    private final PetProfileRepository petProfileRepository;
    // 다른 도메인의 Repository를 직접 참조하지 않는다(구현 가드레일) — Service를 경유한다.
    private final MemberService memberService;

    /*
      반려동물 프로필 등록. SA §8-2, A 도메인 결정 #4.
      보호자 1인당 등록 개수 제한은 없다 — 다반려동물 가구를 고려한 결정.
      Access Token은 유효해도(만료 전) 그 사이 탈퇴 등으로 회원이 존재하지 않을 수 있는 좁은
      race condition을 방어하기 위해, 새 행을 쓰기 전에 활성 회원인지 먼저 확인한다 — 이 확인이
      없으면 이미 탈퇴한 memberId로 고아 프로필이 생성될 수 있다(member_id는 FK 제약이 없는
      순수 Long 값이라 DB가 대신 막아주지 않는다).
     */
    @Transactional
    public PetResponse register(Long memberId, PetCreateRequest request) {
        memberService.assertActiveMember(memberId);

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

    /**
     * 다른 도메인이 활성 반려동물의 소유권과 스냅샷 원본을 함께 확인할 때 사용하는 조회 계약.
     * Soft Delete된 프로필은 엔티티의 SQLRestriction에 의해 조회되지 않는다.
     */
    @Transactional(readOnly = true)
    public Optional<PetResponse> findOwnedActivePet(Long memberId, Long petId) {
        return petProfileRepository.findByIdAndMemberId(petId, memberId)
                .map(PetResponse::from);
    }

    /*
      반려동물 상세 조회. SA §8-2 — "보호자(본인)"만 조회 가능하다.
      존재 자체가 없는 petId와 "존재하지만 다른 회원 소유"인 petId를 구분해서 응답한다
      (전자는 PET_NOT_FOUND 404, 후자는 CommonErrorCode.FORBIDDEN 403). 이 분리 자체는 SA가
      명시한 규정이 아니라 팀 논의로 정한 것이다 — 자세한 근거는 PetErrorCode 참고.
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
      부분 수정(Merge Patch)이다 — PetUpdateRequest에서 생략된(= null) 필드는 PetProfile.update()가
      기존 값을 유지한다. 영속 상태(managed) 엔티티를 그대로 수정해 트랜잭션 커밋 시 더티 체킹으로
      반영한다.
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

    /*
      반려동물 프로필 삭제. SA §8-2 — "보호자(본인)"만 삭제 가능하다. 존재 여부·소유권
      판단은 getPet()·update()와 동일한 원칙을 따른다(PET_NOT_FOUND 404 / FORBIDDEN 403 구분).
      물리 삭제가 아닌 Soft Delete이며, 진행 중 예약이 참조하더라도 삭제를 허용한다
      (PaymentMethod 삭제 정책 SA §4-2와 동일한 원칙 — 과거 이력은 스냅샷으로 보존).
     */
    @Transactional
    public void delete(Long memberId, Long petId) {
        PetProfile petProfile = petProfileRepository.findById(petId)
                .orElseThrow(() -> new ServiceException(PetErrorCode.PET_NOT_FOUND));

        if (!petProfile.getMemberId().equals(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }

        petProfile.markDeleted(LocalDateTime.now());
    }
}
