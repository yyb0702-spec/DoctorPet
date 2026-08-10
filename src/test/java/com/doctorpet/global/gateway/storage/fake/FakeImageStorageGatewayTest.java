package com.doctorpet.global.gateway.storage.fake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — isManagedFileUrl()이 PATCH로 들어온 imageUrl을 이 스토리지가 실제로 발급하는 URL
 * 형식(base URL)과 petId 네임스페이스(keyPrefix) 기준으로 정확히 판별하는지 검증한다. 리뷰 지적
 * 대응(PetService.update()가 저장 전 이 메서드로 신뢰 경계를 검증한다) — createPresignedUploadUrl()
 * 자체는 실제 업로드를 흉내만 내는 로그 출력이라 별도 검증할 상태가 없어 대상에서 뺀다.
 */
class FakeImageStorageGatewayTest {

    private final FakeImageStorageGateway gateway = new FakeImageStorageGateway();

    @Test
    @DisplayName("base URL과 keyPrefix를 모두 만족하면 관리 대상 URL로 판별한다")
    void isManagedFileUrl_matchingBaseUrlAndPrefix_returnsTrue() {
        boolean result = gateway.isManagedFileUrl(
                "http://localhost:9000/fake-bucket/pets/10/uuid.jpg", "pets/10/");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("다른 petId 네임스페이스의 key는 관리 대상이 아니다 — 다른 반려동물 오브젝트 도용 방지")
    void isManagedFileUrl_differentPetNamespace_returnsFalse() {
        boolean result = gateway.isManagedFileUrl(
                "http://localhost:9000/fake-bucket/pets/99/uuid.jpg", "pets/10/");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("이 스토리지가 발급하지 않는 외부 호스트 URL은 관리 대상이 아니다")
    void isManagedFileUrl_externalHost_returnsFalse() {
        boolean result = gateway.isManagedFileUrl(
                "https://evil.example.com/pets/10/uuid.jpg", "pets/10/");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("null 값은 관리 대상이 아니다")
    void isManagedFileUrl_nullArguments_returnsFalse() {
        assertThat(gateway.isManagedFileUrl(null, "pets/10/")).isFalse();
        assertThat(gateway.isManagedFileUrl("http://localhost:9000/fake-bucket/pets/10/uuid.jpg", null))
                .isFalse();
    }
}
