package com.doctorpet.domain.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRateLimitPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("기본 Rate Limit 설정값은 유효하다")
    void validate_defaultProperties_valid() {
        AiRateLimitProperties properties = new AiRateLimitProperties();

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("요청 허용 횟수가 0이면 유효하지 않다")
    void validate_zeroRequestLimit_invalid() {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setAuthenticatedPerMinute(0);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("authenticatedPerMinute");
    }

    @Test
    @DisplayName("분당 제한 시간이 0초이면 유효하지 않다")
    void validate_zeroMinuteWindow_invalid() {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setMinuteWindow(Duration.ZERO);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("minuteWindowPositive");
    }
}
