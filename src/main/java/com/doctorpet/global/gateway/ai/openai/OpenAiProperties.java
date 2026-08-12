package com.doctorpet.global.gateway.ai.openai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ai.openai")
public class OpenAiProperties {

    @NotBlank
    private String baseUrl = "https://api.openai.com/v1";

    @NotBlank
    private String apiKey;

    @NotBlank
    private String model = "gpt-4.1-mini";

    @NotBlank
    private String promptVersion = "doctorpet-ai-v7";

    @Positive
    private int connectTimeoutMs = 2000;

    @Positive
    private int readTimeoutMs = 10000;

    @Positive
    private int circuitBreakerFailureThreshold = 3;

    @Positive
    private long circuitBreakerOpenMs = 30000;
}
