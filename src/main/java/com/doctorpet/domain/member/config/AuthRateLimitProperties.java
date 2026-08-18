package com.doctorpet.domain.member.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 회원가입·이메일 인증 재발송·비밀번호 재설정 요청 rate limit 설정(기능 구멍 점검 대응) —
 * domain.ai.config.AiRateLimitProperties와 같은 이유(AiRateLimiter 참고)로 별도 설정을 둔다.
 *
 * trustedProxies 기본값을 "nginx"로 미리 채워둔 이유(AiRateLimitProperties와의 유일한 차이) —
 * 이 프로젝트의 배포 토폴로지는 nginx가 유일한 리버스 프록시로 고정돼 있어(docker-compose.yml),
 * AI 쪽처럼 별도 환경변수 없이도 즉시 올바르게 동작한다. AI 쪽이 빈 리스트를 기본값으로 둔 건
 * "값을 안 채우면 아무도 신뢰하지 않는" fail-closed를 명시적으로 강제하려는 의도였는데, 이미
 * 그 값이 항상 "nginx" 하나뿐이라는 사실이 이 프로젝트에서 여러 차례 검증됐으므로 여기서는
 * 그 결론을 기본값으로 미리 반영한다 — 필요하면 auth.rate-limit.trusted-proxies로 여전히
 * 덮어쓸 수 있다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "auth.rate-limit")
public class AuthRateLimitProperties {

    // 필드명이 "...PerHour"였다가 "...PerWindow"로 바뀌었다(PR 리뷰 지적 P2) — window(아래)를
    // AUTH_RATE_LIMIT_WINDOW로 1시간이 아닌 값으로 바꿀 수 있는데, 필드명이 "PerHour"로
    // 고정돼 있으면 실제 계약(예: 30분당 5회)과 이름이 어긋난다. "PerWindow"는 실제 기준 단위가
    // window 필드라는 걸 이름 그대로 드러낸다.
    @Positive
    private int signupPerIpPerWindow = 5;

    @Positive
    private int verifyEmailResendPerIpPerWindow = 5;

    @Positive
    private int passwordResetRequestPerIpPerWindow = 5;

    @NotNull
    private Duration window = Duration.ofHours(1);

    private List<String> trustedProxies = new ArrayList<>(List.of("nginx"));

    @AssertTrue(message = "window는 0보다 커야 합니다.")
    public boolean isWindowPositive() {
        return window != null && !window.isZero() && !window.isNegative();
    }
}
