package com.doctorpet.global.gateway.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;

class OpenAiPropertiesTest {

    private Validator validator;
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("유효한 OpenAI 설정은 검증을 통과한다")
    void validProperties_passValidation() {
        assertThat(validator.validate(validProperties())).isEmpty();
    }

    @Test
    @DisplayName("API Key가 비어 있으면 유효하지 않다")
    void blankApiKey_invalid() {
        OpenAiProperties properties = validProperties();
        properties.setApiKey(" ");

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("apiKey");
    }

    @Test
    @DisplayName("timeout과 Circuit Breaker 설정이 0 이하이면 유효하지 않다")
    void nonPositiveOperationalSettings_invalid() {
        OpenAiProperties properties = validProperties();
        properties.setConnectTimeoutMs(0);
        properties.setReadTimeoutMs(-1);
        properties.setCircuitBreakerFailureThreshold(0);
        properties.setCircuitBreakerOpenMs(-1);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "connectTimeoutMs",
                        "readTimeoutMs",
                        "circuitBreakerFailureThreshold",
                        "circuitBreakerOpenMs"
                );
    }

    @Test
    @DisplayName("OpenAI Gateway 선택 시 빈 API Key는 설정 바인딩 단계에서 기동을 막는다")
    void openAiGateway_blankApiKey_contextFails() {
        contextRunner
                .withPropertyValues(
                        "ai.gateway=openai",
                        "ai.openai.api-key="
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Fake Gateway 선택 시 OpenAI API Key가 없어도 OpenAI 설정 검증은 실행되지 않는다")
    void fakeGateway_withoutApiKey_contextStarts() {
        contextRunner
                .withPropertyValues("ai.gateway=fake")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OpenAiGateway.class);
                    assertThat(context).doesNotHaveBean(OpenAiProperties.class);
                });
    }

    private OpenAiProperties validProperties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setApiKey("test-key");
        return properties;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(OpenAiGateway.class)
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
