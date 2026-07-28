package com.doctorpet.domain.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.exception.PetErrorCode;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PetServiceTest {

    @Mock
    private PetProfileRepository petProfileRepository;

    @InjectMocks
    private PetService petService;

    @Test
    @DisplayName("등록 요청을 받으면 인증된 회원 소유로 프로필을 저장하고 응답을 반환한다")
    void register_success() {
        PetCreateRequest request = new PetCreateRequest(
                "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        PetProfile saved = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(saved, 10L);

        given(petProfileRepository.save(any(PetProfile.class))).willReturn(saved);

        PetResponse response = petService.register(1L, request);

        assertThat(response.petId()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("초코");
        assertThat(response.species()).isEqualTo(PetSpecies.DOG);
        assertThat(response.age()).isEqualTo(3);
        assertThat(response.weight()).isEqualByComparingTo("5.4");
        assertThat(response.neutered()).isTrue();

        ArgumentCaptor<PetProfile> captor = ArgumentCaptor.forClass(PetProfile.class);
        verify(petProfileRepository).save(captor.capture());
        // 요청 body가 아니라 인증 주체(memberId)로 소유자를 정한다 — 신뢰 경계 확인.
        assertThat(captor.getValue().getMemberId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("목록 조회는 인증된 회원 소유 프로필만 등록 순서대로 반환한다")
    void getMyPets_success() {
        PetProfile first = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(first, 10L);
        PetProfile second = PetProfile.create(1L, "나비", PetSpecies.CAT, 2, new BigDecimal("3.2"), false);
        setId(second, 11L);
        given(petProfileRepository.findAllByMemberIdOrderByIdAsc(1L)).willReturn(List.of(first, second));

        List<PetResponse> responses = petService.getMyPets(1L);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).petId()).isEqualTo(10L);
        assertThat(responses.get(1).petId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("상세 조회 성공 시 프로필을 반환한다")
    void getPet_success() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));

        PetResponse response = petService.getPet(1L, 10L);

        assertThat(response.petId()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("초코");
    }

    @Test
    @DisplayName("존재하지 않는 petId를 조회하면 PET_NOT_FOUND를 던진다")
    void getPet_notFound() {
        given(petProfileRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> petService.getPet(1L, 999L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(PetErrorCode.PET_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물을 조회하면 FORBIDDEN을 던진다 — 요청 memberId가 아니라 소유자 기준")
    void getPet_notOwner_returnsForbidden() {
        PetProfile petProfile = PetProfile.create(2L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));

        assertThatThrownBy(() -> petService.getPet(1L, 10L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("수정 성공 시 전체 필드가 교체된 프로필을 반환한다")
    void update_success() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));
        PetUpdateRequest request = new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);

        PetResponse response = petService.update(1L, 10L, request);

        assertThat(response.petId()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("초코2");
        assertThat(response.age()).isEqualTo(4);
        assertThat(response.weight()).isEqualByComparingTo("6.0");
        assertThat(response.neutered()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 petId를 수정하면 PET_NOT_FOUND를 던진다")
    void update_notFound() {
        given(petProfileRepository.findById(999L)).willReturn(Optional.empty());
        PetUpdateRequest request = new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);

        assertThatThrownBy(() -> petService.update(1L, 999L, request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(PetErrorCode.PET_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물을 수정하면 FORBIDDEN을 던진다")
    void update_notOwner_returnsForbidden() {
        PetProfile petProfile = PetProfile.create(2L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));
        PetUpdateRequest request = new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false);

        assertThatThrownBy(() -> petService.update(1L, 10L, request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("삭제 성공 시 프로필의 deletedAt이 채워진다(Soft Delete)")
    void delete_success() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));

        petService.delete(1L, 10L);

        assertThat(petProfile.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 petId를 삭제하면 PET_NOT_FOUND를 던진다")
    void delete_notFound() {
        given(petProfileRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> petService.delete(1L, 999L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(PetErrorCode.PET_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물을 삭제하면 FORBIDDEN을 던지고 삭제되지 않는다")
    void delete_notOwner_returnsForbidden() {
        PetProfile petProfile = PetProfile.create(2L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));

        assertThatThrownBy(() -> petService.delete(1L, 10L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThat(petProfile.getDeletedAt()).isNull();
    }

    private void setId(PetProfile petProfile, Long id) {
        try {
            var field = PetProfile.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(petProfile, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
