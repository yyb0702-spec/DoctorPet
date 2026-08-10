package com.doctorpet.global.gateway.storage.s3;

import com.doctorpet.global.gateway.storage.ImageStorageGateway;
import com.doctorpet.global.gateway.storage.PresignedUploadUrl;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
  실제 S3 presigned URL 발급기. S3Presigner 빈은 StorageConfig에서 image.storage.s3.* 설정으로
  구성한다(SmtpEmailGateway가 MailConfig의 JavaMailSender를 쓰는 것과 동일한 구조).
  버킷은 퍼블릭 읽기 정책 또는 CloudFront 등으로 별도 서빙한다고 가정하고, fileUrl은 버킷의
  가상 호스팅 스타일 URL을 그대로 구성한다 — 실제 배포 시 CDN 도입 등은 인프라 설정으로
  이 클래스 변경 없이 대응 가능하다(추후 필요하면 baseUrl을 설정으로 분리).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "image.storage.provider", havingValue = "s3")
public class S3ImageStorageGateway implements ImageStorageGateway {

    private final S3Presigner s3Presigner;
    private final ImageStorageS3Properties properties;

    @Override
    public PresignedUploadUrl createPresignedUploadUrl(String key, String contentType) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.getPresignTtlSeconds()))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);

        String fileUrl = baseUrl() + key;

        return new PresignedUploadUrl(presigned.url().toString(), fileUrl, properties.getPresignTtlSeconds());
    }

    @Override
    public boolean isManagedFileUrl(String fileUrl, String keyPrefix) {
        if (fileUrl == null || keyPrefix == null || !fileUrl.startsWith(baseUrl())) {
            return false;
        }
        String key = fileUrl.substring(baseUrl().length());
        return key.startsWith(keyPrefix);
    }

    // createPresignedUploadUrl()이 만드는 fileUrl과 isManagedFileUrl()의 검증 기준이 어긋나지
    // 않도록 버킷 가상 호스팅 스타일 base URL을 한 곳에서만 구성한다.
    private String baseUrl() {
        return "https://%s.s3.%s.amazonaws.com/".formatted(properties.getBucket(), properties.getRegion());
    }
}
