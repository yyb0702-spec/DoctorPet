package com.doctorpet.global.config;

import com.doctorpet.global.gateway.storage.s3.ImageStorageS3Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
  image.storage.provider=s3일 때만 실제 S3Presigner 빈을 등록한다(MailConfig와 동일한 fail-safe
  관례 — provider가 비어 있거나 fake면 이 빈도, S3ImageStorageGateway가 아닌 FakeImageStorageGateway가
  등록된다). 자격 증명은 AWS SDK 기본 체인(환경 변수·IAM 역할 등)을 그대로 쓴다.
 */
@Configuration
@EnableConfigurationProperties(ImageStorageS3Properties.class)
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "image.storage.provider", havingValue = "s3")
    public S3Presigner s3Presigner(ImageStorageS3Properties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .build();
    }
}
