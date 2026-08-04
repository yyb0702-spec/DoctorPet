package com.doctorpet.domain.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Level 1 — 진료비 청구 재시도 설정값 검증(PR #92 P2). retry-backoff-deadline-ms가 0·음수면 모든 재시도가
 * 조용히 비활성화되므로 @Validated로 걸러지는지, 잘못된 값에서 컨텍스트 기동이 실패하는지 확인한다.
 */
class PaymentChargePropertiesTest {

    private Validator validator;
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("기본 데드라인 캡 값은 유효하다")
    void defaults_valid() {
        assertThat(validator.validate(new PaymentChargeProperties())).isEmpty();
    }

    @Test
    @DisplayName("retry-backoff-deadline-ms가 0이면 유효하지 않다(모든 재시도가 조용히 비활성화되는 것 방지)")
    void zeroDeadline_invalid() {
        PaymentChargeProperties properties = new PaymentChargeProperties();
        properties.setRetryBackoffDeadlineMs(0L);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("retryBackoffDeadlineMs");
    }

    @Test
    @DisplayName("retry-backoff-deadline-ms가 음수이면 유효하지 않다")
    void negativeDeadline_invalid() {
        PaymentChargeProperties properties = new PaymentChargeProperties();
        properties.setRetryBackoffDeadlineMs(-1L);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("retryBackoffDeadlineMs");
    }

    @Test
    @DisplayName("retry-backoff-deadline-ms가 0이면 설정 바인딩 단계에서 애플리케이션 기동에 실패한다")
    void bind_zeroDeadline_contextFails() {
        contextRunner
                .withPropertyValues("payment.charge.retry-backoff-deadline-ms=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("retry-backoff-deadline-ms가 음수이면 설정 바인딩 단계에서 애플리케이션 기동에 실패한다")
    void bind_negativeDeadline_contextFails() {
        contextRunner
                .withPropertyValues("payment.charge.retry-backoff-deadline-ms=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentChargeProperties.class)
    static class TestConfig {
    }
}
