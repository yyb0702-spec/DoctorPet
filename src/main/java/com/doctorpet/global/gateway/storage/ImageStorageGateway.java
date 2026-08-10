package com.doctorpet.global.gateway.storage;

/*
  이미지 업로드용 오브젝트 스토리지 게이트웨이. EmailGateway/PaymentGateway와 동일한 패턴을
  따른다 — 구현체는 image.storage.provider 설정값에 따라 조건부로 하나만 등록된다
  (FakeImageStorageGateway/S3ImageStorageGateway, StorageConfig 참고). 인터페이스는 특정
  스토리지(S3 등)를 몰라야 하므로 presigned URL 발급만 계약으로 둔다.

  파일 바이트 자체는 서버를 거치지 않는다 — 클라이언트가 이 메서드로 받은 uploadUrl에 직접
  PUT으로 업로드하고, 완료 후 fileUrl을 PATCH /api/pets/{petId}로 저장한다(서버 부하·대역폭을
  피하기 위한 설계 — presigned URL 방식, 사용자 확인 완료).
 */
public interface ImageStorageGateway {

    /**
     * 지정한 key·contentType으로 단건 업로드용 presigned URL을 발급한다.
     *
     * @param key         버킷 내 오브젝트 경로(예: pets/1/uuid.jpg)
     * @param contentType 업로드할 파일의 MIME 타입(허용 목록 검증은 상위 서비스 책임)
     */
    PresignedUploadUrl createPresignedUploadUrl(String key, String contentType);

    /**
     * PATCH로 확정 저장하려는 fileUrl이 이 스토리지가 실제로 발급 가능한 오브젝트 URL 형식이며,
     * 지정한 keyPrefix(예: {@code pets/1/})로 시작하는 key를 가리키는지 확인한다. 리뷰 지적
     * 대응 — imageUrl은 인증된 클라이언트가 보내는 요청 값이라 신뢰 경계 밖에 있는데도 기존에는
     * 길이만 검사해, 업로드 절차를 거치지 않은 임의 URL이나 다른 반려동물의 오브젝트 URL도 그대로
     * 저장·노출될 수 있었다. 이 메서드는 최소한의 방어선으로 스킴·호스트·경로(petId 네임스페이스)를
     * 검증한다 — 발급한 key를 회원·petId와 연결해 별도로 추적·확인하는 것(더 강한 보장)은 발급
     * 기록을 영속화해야 하는 더 큰 변경이라 이번 범위에서는 다루지 않는다.
     *
     * @param fileUrl   저장하려는 이미지 URL(클라이언트가 PATCH 요청으로 보낸 값)
     * @param keyPrefix 이 요청이 속한 petId 네임스페이스 prefix(예: {@code pets/1/})
     */
    boolean isManagedFileUrl(String fileUrl, String keyPrefix);
}
