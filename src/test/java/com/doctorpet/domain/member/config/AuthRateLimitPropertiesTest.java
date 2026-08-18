package com.doctorpet.domain.member.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthRateLimitPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("기본 설정값은 유효하고, trusted-proxies는 nginx로 미리 채워져 있다")
    void validate_defaultProperties_valid() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getTrustedProxies()).containsExactly("nginx");
    }

    @Test
    @DisplayName("signup-per-ip-per-window가 0이면 유효하지 않다")
    void validate_zeroSignupLimit_invalid() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setSignupPerIpPerWindow(0);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("signupPerIpPerWindow");
    }

    @Test
    @DisplayName("window가 0이면 유효하지 않다")
    void validate_zeroWindow_invalid() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setWindow(Duration.ZERO);

        assertThat(validator.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("windowPositive");
    }

    @Test
    @DisplayName("trusted-proxies는 그대로 덮어쓸 수 있다")
    void trustedProxies_overridable() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.1"));

        assertThat(properties.getTrustedProxies()).containsExactly("10.0.0.1");
    }
}
