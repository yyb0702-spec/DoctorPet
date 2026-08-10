package com.doctorpet.domain.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.pet.dto.request.PetCreateRequest;
import com.doctorpet.domain.pet.dto.request.PetUpdateRequest;
import com.doctorpet.domain.pet.dto.response.PetImageUploadUrlResponse;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.exception.PetErrorCode;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.storage.ImageStorageGateway;
import com.doctorpet.global.gateway.storage.PresignedUploadUrl;
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

    @Mock
    private MemberService memberService;

    @Mock
    private ImageStorageGateway imageStorageGateway;

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
    @DisplayName("탈퇴 등으로 이미 존재하지 않는 회원이면 MEMBER_NOT_FOUND를 던지고 저장하지 않는다")
    void register_memberNotFound() {
        PetCreateRequest request = new PetCreateRequest(
                "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        willThrow(new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND))
                .given(memberService).assertActiveMember(1L);

        assertThatThrownBy(() -> petService.register(1L, request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        verify(petProfileRepository, never()).save(any(PetProfile.class));
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
        PetUpdateRequest request =
                new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false, null);

        PetResponse response = petService.update(1L, 10L, request);

        assertThat(response.petId()).isEqualTo(10L);
        assertThat(response.name()).isEqualTo("초코2");
        assertThat(response.age()).isEqualTo(4);
        assertThat(response.weight()).isEqualByComparingTo("6.0");
        assertThat(response.neutered()).isFalse();
    }

    @Test
    @DisplayName("imageUrl만 보내면 다른 필드는 유지한 채 이미지 URL만 저장된다(presigned URL 업로드 확정 흐름)")
    void update_imageUrlOnly_savesImageAndKeepsOtherFields() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));
        given(imageStorageGateway.isManagedFileUrl(
                "http://localhost:9000/fake-bucket/pets/10/uuid.jpg", "pets/10/"))
                .willReturn(true);
        PetUpdateRequest request = new PetUpdateRequest(
                null, null, null, null, null, "http://localhost:9000/fake-bucket/pets/10/uuid.jpg");

        PetResponse response = petService.update(1L, 10L, request);

        assertThat(response.name()).isEqualTo("초코");
        assertThat(response.imageUrl()).isEqualTo("http://localhost:9000/fake-bucket/pets/10/uuid.jpg");
    }

    @Test
    @DisplayName("이 스토리지가 발급하지 않았거나 다른 petId 네임스페이스의 imageUrl은 INVALID_IMAGE_URL을 던지고 저장하지 않는다")
    void update_imageUrlNotManagedByGateway_throwsInvalidImageUrl() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));
        // 게이트웨이가 관리하지 않는 URL(예: 업로드 절차를 거치지 않은 임의 외부 URL, 또는 다른
        // petId 네임스페이스의 오브젝트 URL)이면 isManagedFileUrl()이 false를 반환한다 — 실제
        // 스킴·호스트·prefix 판별 로직 자체는 FakeImageStorageGatewayTest/S3ImageStorageGatewayTest에서
        // 검증하므로, 여기서는 PetService가 그 결과에 맞춰 저장을 막는지만 확인한다.
        given(imageStorageGateway.isManagedFileUrl("https://evil.example.com/pets/10/x.jpg", "pets/10/"))
                .willReturn(false);
        PetUpdateRequest request = new PetUpdateRequest(
                null, null, null, null, null, "https://evil.example.com/pets/10/x.jpg");

        assertThatThrownBy(() -> petService.update(1L, 10L, request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(PetErrorCode.INVALID_IMAGE_URL);
        // 검증에 실패하면 병합조차 하지 않아야 한다 — 엔티티에 반영되지 않았는지 확인.
        assertThat(petProfile.getImageUrl()).isNull();
    }

    @Test
    @DisplayName("부분 수정 — 생략한(null) 필드는 기존 값을 유지하고 지정한 필드만 바뀐다")
    void update_partialUpdate_onlyProvidedFieldsChange() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));
        // weight만 보내고 나머지는 생략(null) — PATCH의 일반적인 부분 수정 형태.
        PetUpdateRequest request = new PetUpdateRequest(null, null, null, new BigDecimal("6.0"), null, null);

        PetResponse response = petService.update(1L, 10L, request);

        assertThat(response.name()).isEqualTo("초코");
        assertThat(response.species()).isEqualTo(PetSpecies.DOG);
        assertThat(response.age()).isEqualTo(3);
        assertThat(response.weight()).isEqualByComparingTo("6.0");
        assertThat(response.neutered()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 petId를 수정하면 PET_NOT_FOUND를 던진다")
    void update_notFound() {
        given(petProfileRepository.findById(999L)).willReturn(Optional.empty());
        PetUpdateRequest request =
                new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false, null);

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
        PetUpdateRequest request =
                new PetUpdateRequest("초코2", PetSpecies.DOG, 4, new BigDecimal("6.0"), false, null);

        assertThatThrownBy(() -> petService.update(1L, 10L, request))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("업로드 URL 발급 성공 시 게이트웨이가 만든 key로 presigned URL을 반환한다")
    void createImageUploadUrl_success() {
        PetProfile petProfile = PetProfile.create(1L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));
        given(imageStorageGateway.createPresignedUploadUrl(any(), any())).willReturn(
                new PresignedUploadUrl(
                        "https://bucket.s3.ap-northeast-2.amazonaws.com/pets/10/uuid.jpg?sig=abc",
                        "https://bucket.s3.ap-northeast-2.amazonaws.com/pets/10/uuid.jpg",
                        300
                )
        );

        PetImageUploadUrlResponse response = petService.createImageUploadUrl(1L, 10L, "image/jpeg");

        assertThat(response.uploadUrl()).contains("sig=abc");
        assertThat(response.imageUrl()).isEqualTo("https://bucket.s3.ap-northeast-2.amazonaws.com/pets/10/uuid.jpg");
        assertThat(response.expiresInSeconds()).isEqualTo(300);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageStorageGateway).createPresignedUploadUrl(keyCaptor.capture(), eq("image/jpeg"));
        // key는 petId로 네임스페이스를 나누고 jpeg 확장자를 붙인다 — 다른 반려동물과 충돌하지 않는다.
        assertThat(keyCaptor.getValue()).startsWith("pets/10/").endsWith(".jpeg");
    }

    @Test
    @DisplayName("존재하지 않는 petId로 업로드 URL을 요청하면 PET_NOT_FOUND를 던진다")
    void createImageUploadUrl_notFound() {
        given(petProfileRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> petService.createImageUploadUrl(1L, 999L, "image/jpeg"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(PetErrorCode.PET_NOT_FOUND);
        verify(imageStorageGateway, never()).createPresignedUploadUrl(any(), any());
    }

    @Test
    @DisplayName("다른 회원 소유의 반려동물에 업로드 URL을 요청하면 FORBIDDEN을 던진다")
    void createImageUploadUrl_notOwner_returnsForbidden() {
        PetProfile petProfile = PetProfile.create(2L, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true);
        setId(petProfile, 10L);
        given(petProfileRepository.findById(10L)).willReturn(Optional.of(petProfile));

        assertThatThrownBy(() -> petService.createImageUploadUrl(1L, 10L, "image/jpeg"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verify(imageStorageGateway, never()).createPresignedUploadUrl(any(), any());
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

    @Test
    @DisplayName("예약 연동 조회는 인증 회원 소유의 활성 프로필을 응답한다")
    void findOwnedActivePet_returnsOwnedPet() {
        PetProfile petProfile = PetProfile.create(
                1L,
                "초코",
                PetSpecies.DOG,
                3,
                new BigDecimal("5.4"),
                true
        );
        setId(petProfile, 10L);
        given(petProfileRepository.findByIdAndMemberId(10L, 1L))
                .willReturn(Optional.of(petProfile));

        Optional<PetResponse> response =
                petService.findOwnedActivePet(1L, 10L);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().name()).isEqualTo("초코");
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
