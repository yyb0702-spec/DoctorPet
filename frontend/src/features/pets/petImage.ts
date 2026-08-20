// 펫 프로필 이미지 업로드 전 클라이언트 검증.
// 서버도 contentType을 image/jpeg|png|webp로 제한하지만(PetImageUploadUrlRequest @Pattern),
// 발급 요청을 보내기 전에 걸러 잘못된 파일로 업로드 URL을 낭비하지 않는다.

// 서버 허용 목록과 같은 3종. 여기 값을 늘리려면 백엔드 @Pattern부터 바꿔야 한다.
export const PET_IMAGE_CONTENT_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const

// 프로필 사진 한 장의 상한. 서버·S3가 강제하는 값이 아니라 화면 정책이다(느린 업로드 방지).
export const PET_IMAGE_MAX_BYTES = 5 * 1024 * 1024

/** 업로드 가능하면 null, 아니면 사용자에게 보여줄 이유를 돌려준다. */
export function validatePetImageFile(file: File): string | null {
  if (!PET_IMAGE_CONTENT_TYPES.includes(file.type as never)) {
    return 'JPG·PNG·WEBP 이미지만 올릴 수 있어요.'
  }
  if (file.size > PET_IMAGE_MAX_BYTES) {
    return '이미지 크기는 5MB 이하여야 해요.'
  }
  return null
}
