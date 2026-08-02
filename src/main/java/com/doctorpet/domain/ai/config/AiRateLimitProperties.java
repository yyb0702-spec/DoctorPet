package com.doctorpet.domain.ai.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.rate-limit")
public class AiRateLimitProperties {

    private int authenticatedPerMinute = 5;
    private int anonymousPerMinute = 3;
    private int anonymousPerDay = 30;
    private Duration minuteWindow = Duration.ofMinutes(1);
    private List<String> trustedProxies = new ArrayList<>();
}
