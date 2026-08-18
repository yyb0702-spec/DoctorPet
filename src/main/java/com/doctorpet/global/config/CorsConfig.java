package com.doctorpet.global.config;

import java.time.Duration;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 설정(기능 구멍 점검 대응) — 지금까지는 CORS 설정 자체가 없었다. 아직 프론트가 없어
 * (AuthController의 "프론트가 아직 없어" 주석 참고) 당장은 문제가 안 됐지만, 브라우저 기반
 * 프론트가 다른 오리진에서 이 API를 호출하는 순간 모든 요청이 preflight에서 막힌다.
 *
 * {@code @Configuration}/{@code @Bean}으로 별도 스프링 빈을 두지 않고 순수 정적 팩터리로만
 * 두는 이유(리뷰 대응) — SecurityConfig.filterChain()이 이 값을 빈 주입이 아니라
 * {@code @Value}로 직접 읽어 여기 넘겨주기 때문이다. 빈으로 등록하면 SecurityConfig를
 * {@code @Import}하는 기존 @WebMvcTest 슬라이스 테스트(29곳 이상)가 이 빈을 각각 mock으로
 * 추가해야만 컨텍스트 로딩에 성공하는데, 이 설정은 그 정도로 무거울 이유가 없는 단순
 * "오리진 리스트 → CorsConfigurationSource 변환"이라 순수 함수로 두는 편이 테스트도 더
 * 가볍다(CorsConfigTest는 스프링 컨텍스트 없이 이 클래스만 직접 테스트한다).
 *
 * allowCredentials를 켜지 않는 이유 — 이 API는 인증에 쿠키가 아니라 Authorization 헤더(Bearer
 * Access Token)를 쓴다(JwtAuthenticationFilter). CORS의 credentials는 쿠키·HTTP 인증·클라이언트
 * 인증서에 대한 것이라 이 프로젝트의 인증 방식과 무관하다 — 켜면 allowedOrigins에 "*"를 쓸 수
 * 없게 되는 제약만 추가로 생긴다.
 *
 * allowedHeaders에 Authorization을 명시하는 이유 — Bearer 토큰을 담은 커스텀 헤더라 브라우저가
 * preflight(OPTIONS)로 먼저 서버에 허용 여부를 묻는데, 이 헤더가 허용 목록에 없으면 실제 요청
 * 자체가 브라우저 단에서 막힌다.
 *
 * allowedOrigins가 빈 리스트면(기본값, application.yaml의 cors.allowed-origins 참고) 어떤
 * 오리진도 허용하지 않는다(fail-closed) — Origin 헤더가 없는 요청(브라우저가 아닌 대부분의
 * 현재 트래픽)에는 애초에 영향이 없다.
 */
public final class CorsConfig {

    private CorsConfig() {
    }

    public static CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
