package com.doctorpet.global.gateway.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.global.gateway.storage.ImageStorageGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — isManagedFileUrl()이 S3 가상 호스팅 스타일 base URL(버킷·리전)과 petId 네임스페이스
 * (keyPrefix) 기준으로 PATCH imageUrl을 정확히 판별하는지 검증한다. 실제 presigned URL 발급
 * (createPresignedUploadUrl)은 S3Presigner 호출이 필요해 여기서는 검증하지 않는다 — S3Presigner는
 * isManagedFileUrl 경로에서 쓰이지 않으므로 null로 주입해도 안전하다.
 */
class S3ImageStorageGatewayTest {

    private final ImageStorageS3Properties properties = new ImageStorageS3Properties();
    private final ImageStorageGateway gateway;

    S3ImageStorageGatewayTest() {
        properties.setBucket("doctorpet-bucket");
        properties.setRegion("ap-northeast-2");
        this.gateway = new S3ImageStorageGateway(null, properties);
    }

    @Test
    @DisplayName("버킷·리전 base URL과 keyPrefix를 모두 만족하면 관리 대상 URL로 판별한다")
    void isManagedFileUrl_matchingBaseUrlAndPrefix_returnsTrue() {
        boolean result = gateway.isManagedFileUrl(
                "https://doctorpet-bucket.s3.ap-northeast-2.amazonaws.com/pets/10/uuid.jpg", "pets/10/");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("다른 petId 네임스페이스의 key는 관리 대상이 아니다 — 다른 반려동물 오브젝트 도용 방지")
    void isManagedFileUrl_differentPetNamespace_returnsFalse() {
        boolean result = gateway.isManagedFileUrl(
                "https://doctorpet-bucket.s3.ap-northeast-2.amazonaws.com/pets/99/uuid.jpg", "pets/10/");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("다른 버킷·리전이거나 이 스토리지가 발급하지 않는 외부 호스트 URL은 관리 대상이 아니다")
    void isManagedFileUrl_externalHost_returnsFalse() {
        assertThat(gateway.isManagedFileUrl("https://evil.example.com/pets/10/uuid.jpg", "pets/10/"))
                .isFalse();
        assertThat(gateway.isManagedFileUrl(
                "https://other-bucket.s3.ap-northeast-2.amazonaws.com/pets/10/uuid.jpg", "pets/10/"))
                .isFalse();
    }

    @Test
    @DisplayName("null 값은 관리 대상이 아니다")
    void isManagedFileUrl_nullArguments_returnsFalse() {
        assertThat(gateway.isManagedFileUrl(null, "pets/10/")).isFalse();
        assertThat(gateway.isManagedFileUrl(
                "https://doctorpet-bucket.s3.ap-northeast-2.amazonaws.com/pets/10/uuid.jpg", null))
                .isFalse();
    }
}
