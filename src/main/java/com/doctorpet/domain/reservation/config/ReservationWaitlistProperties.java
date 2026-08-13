package com.doctorpet.domain.reservation.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "reservation.waitlist")
public class ReservationWaitlistProperties {

    /** 보호자가 승급 제안에 응답할 수 있는 시간. 확정 정책: 10분. */
    @Positive
    private int offerValidityMinutes = 10;

    @Positive
    private long expirationIntervalMs = 60_000L;

    @PositiveOrZero
    private long expirationInitialDelayMs = 60_000L;

    @Positive
    private int expirationBatchSize = 100;
}
