package com.doctorpet.domain.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AiRetentionPropertiesTest {

    private Validator validator;
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("기본 증상 보존 일수는 유효하다")
    void validate_defaultProperties_valid() {
        AiRetentionProperties properties = new AiRetentionProperties();

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("증상 보존 일수가 0이면 유효하지 않다")
    void validate_zeroRetentionDays_invalid() {
        AiRetentionProperties properties = new AiRetentionProperties();
        properties.setSymptomTextDays(0);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("symptomTextDays");
    }

    @Test
    @DisplayName("증상 보존 일수가 음수이면 유효하지 않다")
    void validate_negativeRetentionDays_invalid() {
        AiRetentionProperties properties = new AiRetentionProperties();
        properties.setSymptomTextDays(-1);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("symptomTextDays");
    }

    @Test
    @DisplayName("보존 일수가 0이면 설정 바인딩 단계에서 애플리케이션 기동에 실패한다")
    void bind_zeroRetentionDays_contextFails() {
        contextRunner
                .withPropertyValues("ai.retention.symptom-text-days=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("보존 일수가 음수이면 설정 바인딩 단계에서 애플리케이션 기동에 실패한다")
    void bind_negativeRetentionDays_contextFails() {
        contextRunner
                .withPropertyValues("ai.retention.symptom-text-days=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiRetentionProperties.class)
    static class TestConfig {
    }
}
