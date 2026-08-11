package com.doctorpet.global.gateway.storage;

/**
 * presigned 업로드 URL 발급 결과.
 *
 * @param uploadUrl        클라이언트가 파일 바이트를 PUT으로 직접 업로드할 임시 URL
 * @param fileUrl           업로드 완료 후 영구적으로 파일을 가리킬 URL(저장용)
 * @param expiresInSeconds uploadUrl의 유효 시간(초)
 */
public record PresignedUploadUrl(String uploadUrl, String fileUrl, int expiresInSeconds) {
}
