package com.doctorpet.domain.hospital.publicdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application-local.yml의 동물병원 OpenAPI 설정값을 매핑합니다.
 */
@ConfigurationProperties(prefix = "public-data.animal-hospital")
public record AnimalHospitalApiProperties(
        String baseUrl,
        String serviceKey,
        int pageSize,
        Duration connectTimeout,
        Duration readTimeout,
        String sourceCrs,
        int refreshMaxAttempts,
        Duration refreshRetryDelay
) {

    private static final String DEFAULT_BASE_URL =
            "https://apis.data.go.kr/1741000/animal_hospitals";
    private static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT =
            Duration.ofSeconds(10);
    private static final String DEFAULT_SOURCE_CRS = "EPSG:5174";
    private static final int DEFAULT_REFRESH_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_REFRESH_RETRY_DELAY =
            Duration.ofMinutes(5);

    public AnimalHospitalApiProperties {
        // 로컬 설정이 없어도 시드 비활성 환경의 애플리케이션이 안전하게 시작되도록 기본값을 둡니다.
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (connectTimeout == null) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
        if (sourceCrs == null || sourceCrs.isBlank()) {
            sourceCrs = DEFAULT_SOURCE_CRS;
        }
        if (refreshMaxAttempts <= 0) {
            refreshMaxAttempts = DEFAULT_REFRESH_MAX_ATTEMPTS;
        }
        if (refreshRetryDelay == null
                || refreshRetryDelay.isNegative()
                || refreshRetryDelay.isZero()) {
            refreshRetryDelay = DEFAULT_REFRESH_RETRY_DELAY;
        }
    }
}
