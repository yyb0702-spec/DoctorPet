package com.doctorpet.domain.ai.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "ai.rate-limit")
public class AiRateLimitProperties {

    @Positive
    private int authenticatedPerMinute = 5;

    @Positive
    private int anonymousPerMinute = 3;

    @Positive
    private int anonymousPerDay = 30;

    @NotNull
    private Duration minuteWindow = Duration.ofMinutes(1);

    private List<String> trustedProxies = new ArrayList<>();

    @AssertTrue(message = "minuteWindow는 0보다 커야 합니다.")
    public boolean isMinuteWindowPositive() {
        return minuteWindow != null && !minuteWindow.isZero() && !minuteWindow.isNegative();
    }
}
