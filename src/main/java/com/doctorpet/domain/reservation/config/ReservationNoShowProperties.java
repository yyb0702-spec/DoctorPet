package com.doctorpet.domain.reservation.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.ZoneId;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "reservation.no-show")
public class ReservationNoShowProperties {

    @Positive private long intervalMs = 60_000L;
    @PositiveOrZero private long initialDelayMs = 60_000L;
    private ZoneId zoneId = ZoneId.of("Asia/Seoul");
    @Positive private int graceMinutes = 10;
    @Positive private int batchSize = 100;
    @Positive private int maxScannedPerRun = 1_000;
    @Positive private int maxAttempts = 2;
    @PositiveOrZero private int lockWaitSeconds = 0;
}
