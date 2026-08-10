package com.doctorpet.global.gateway.storage.fake;

import com.doctorpet.global.gateway.storage.ImageStorageGateway;
import com.doctorpet.global.gateway.storage.PresignedUploadUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/*
  로컬/테스트용 가짜 스토리지. FakeEmailGateway와 같은 이유로 존재한다 — image.storage.provider를
  설정하지 않으면 이 빈도 S3ImageStorageGateway도 등록되지 않아(fail-safe), 실수로 아무 게이트웨이도
  없는 채 서비스가 업로드 URL 발급 지점에서 NoSuchBeanDefinitionException으로 즉시 실패하게 만든다.
  실제 S3를 호출하지 않고 로그만 남기며, 로컬 개발 시에는 반환된 uploadUrl로 실제 업로드는 되지
  않는다(로컬에서 이미지 업로드까지 검증하려면 image.storage.provider=s3 + 실제 자격 증명 필요).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "image.storage.provider", havingValue = "fake")
public class FakeImageStorageGateway implements ImageStorageGateway {

    private static final String FAKE_BASE_URL = "http://localhost:9000/fake-bucket/";
    private static final int FAKE_EXPIRES_IN_SECONDS = 300;

    @Override
    public PresignedUploadUrl createPresignedUploadUrl(String key, String contentType) {
        log.info("[FAKE STORAGE] key={}, contentType={}", key, contentType);
        String fileUrl = FAKE_BASE_URL + key;
        String uploadUrl = fileUrl + "?fake-presigned=true";
        return new PresignedUploadUrl(uploadUrl, fileUrl, FAKE_EXPIRES_IN_SECONDS);
    }
}
