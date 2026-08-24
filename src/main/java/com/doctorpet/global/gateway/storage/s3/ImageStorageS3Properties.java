package com.doctorpet.global.gateway.storage.s3;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/*
  실제 S3 업로드 설정(image.storage.provider=s3일 때만 사용). MailSmtpProperties(mail.smtp.*)와
  같은 패턴 — @Getter @Setter로 두는 이유도 동일하다(ConfigurationProperties 바인딩).
  accessKey·secretKey는 여기 두지 않는다 — AWS SDK 기본 자격 증명 체인(환경 변수·IAM 역할 등)을
  그대로 쓴다(AGENTS.md 보안 절 — 인증정보를 코드·문서·로그에 남기지 않는다).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "image.storage.s3")
public class ImageStorageS3Properties {

    private String bucket;
    private String region;
    private int presignTtlSeconds = 300;
}
