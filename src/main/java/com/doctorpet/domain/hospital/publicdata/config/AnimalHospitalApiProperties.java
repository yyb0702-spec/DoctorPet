package com.doctorpet.domain.hospital.publicdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * application-local.yml의 동물병원 OpenAPI 설정값을 매핑합니다.
 */
@ConfigurationProperties(prefix = "public-data.animal-hospital")
public record AnimalHospitalApiProperties(
        String baseUrl,
        String serviceKey,
        int pageSize,
        List<String> localGovernmentCodes
) {
}
