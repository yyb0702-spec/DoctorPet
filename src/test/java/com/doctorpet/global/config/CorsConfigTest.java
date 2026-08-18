package com.doctorpet.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/**
 * CORS 설정(기능 구멍 점검 대응). 기본값(빈 오리진 리스트)이면 어떤 오리진도 허용하지 않는지
 * (fail-closed), 오리진을 채우면 정확히 그 오리진만 허용하는지 검증한다. 스프링 컨텍스트 없이
 * 순수 정적 팩터리만 직접 호출한다(SecurityConfig가 이 값을 빈이 아니라 @Value로 주입받는
 * 이유는 CorsConfig 클래스 주석 참고).
 */
class CorsConfigTest {

    @Test
    @DisplayName("허용 오리진을 채우지 않으면(기본값) 어떤 오리진도 허용하지 않는다(fail-closed)")
    void corsConfigurationSource_emptyOrigins_allowsNoOrigin() {
        CorsConfiguration configuration = CorsConfig.corsConfigurationSource(List.of())
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/auth/login"));

        assertThat(configuration.checkOrigin("https://app.doctorpet.example")).isNull();
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    @DisplayName("허용 오리진을 채우면 그 오리진만 허용하고, 다른 오리진은 여전히 거부한다")
    void corsConfigurationSource_configuredOrigin_allowsOnlyThatOrigin() {
        CorsConfiguration configuration = CorsConfig
                .corsConfigurationSource(List.of("https://app.doctorpet.example"))
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/auth/login"));

        assertThat(configuration.checkOrigin("https://app.doctorpet.example"))
                .isEqualTo("https://app.doctorpet.example");
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    @DisplayName("Authorization 헤더 인증 방식이라 credentials 모드는 켜지 않는다")
    void corsConfigurationSource_doesNotAllowCredentials() {
        CorsConfiguration configuration = CorsConfig.corsConfigurationSource(List.of())
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/auth/login"));

        assertThat(configuration.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("Authorization 헤더는 preflight 허용 목록에 포함된다 — 없으면 Bearer 토큰 요청 자체가 브라우저 단에서 막힌다")
    void corsConfigurationSource_allowsAuthorizationHeader() {
        CorsConfiguration configuration = CorsConfig.corsConfigurationSource(List.of())
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/auth/login"));

        assertThat(configuration.getAllowedHeaders()).contains("Authorization");
    }
}
