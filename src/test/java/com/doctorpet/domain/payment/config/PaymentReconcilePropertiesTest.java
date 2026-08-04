package com.doctorpet.domain.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — 결제 정산 설정값 검증(PR #81 P2). 잘못된 값(0 배치·음수 임계 등)이 @Validated로 걸러지는지 확인한다.
 * 실제 시작 시점 거부는 @ConfigurationProperties + @Validated가 이 제약을 그대로 적용해 이뤄진다.
 */
class PaymentReconcilePropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("기본 설정값은 유효하다")
    void defaults_valid() {
        assertThat(validator.validate(new PaymentReconcileProperties())).isEmpty();
    }

    @Test
    @DisplayName("batch-size가 0이면 유효하지 않다(PageRequest.of(0,0) 실패 방지)")
    void zeroBatchSize_invalid() {
        PaymentReconcileProperties properties = new PaymentReconcileProperties();
        properties.setBatchSize(0);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("batchSize");
    }

    @Test
    @DisplayName("max-attempts가 0이면 유효하지 않다(첫 조회부터 RECONCILE_STUCK 방지)")
    void zeroMaxAttempts_invalid() {
        PaymentReconcileProperties properties = new PaymentReconcileProperties();
        properties.setMaxAttempts(0);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("maxAttempts");
    }

    @Test
    @DisplayName("interval-ms가 0이면 유효하지 않다(스케줄링 성립 불가)")
    void zeroIntervalMs_invalid() {
        PaymentReconcileProperties properties = new PaymentReconcileProperties();
        properties.setIntervalMs(0);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("intervalMs");
    }

    @Test
    @DisplayName("stale-after-ms가 음수이면 유효하지 않다(임계가 미래가 되어 방금 생성된 PENDING까지 대상 포함)")
    void negativeStaleAfterMs_invalid() {
        PaymentReconcileProperties properties = new PaymentReconcileProperties();
        properties.setStaleAfterMs(-1L);

        assertThat(validator.validate(properties))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("staleAfterMs");
    }

    @Test
    @DisplayName("stale-after-ms가 0이면 유효하다(유예 없이 즉시 대상 — 음수만 금지)")
    void zeroStaleAfterMs_valid() {
        PaymentReconcileProperties properties = new PaymentReconcileProperties();
        properties.setStaleAfterMs(0L);

        assertThat(validator.validate(properties)).isEmpty();
    }
}
