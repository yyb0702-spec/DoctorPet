package com.doctorpet.domain.hospital.publicdata.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 공공데이터 설정 클래스를 Spring Bean으로 등록합니다.
 */
@Configuration
@EnableConfigurationProperties(AnimalHospitalApiProperties.class)
public class PublicDataConfig {
}
