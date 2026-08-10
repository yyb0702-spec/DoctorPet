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
}
