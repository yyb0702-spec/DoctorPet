package com.doctorpet.global.config;

import java.time.Duration;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 설정(기능 구멍 점검 대응) — 지금까지는 CORS 설정 자체가 없었다.
 *
 * (리뷰 지적 P2 정정) 이 클래스 주석은 원래 "아직 프론트가 없어 당장은 문제가 안 된다"고 썼는데
 * 틀렸다 — {@code frontend/}는 이미 develop에 있고, dev 서버({@code vite.config.ts})가 `/api`를
 * 이 백엔드로 실제로 프록시해 호출한다. CORS를 아예 설정하지 않았던 지금까지는 Spring Security가
 * Origin 헤더를 아예 검사하지 않아 우연히 문제가 안 됐던 것뿐이고, 이 클래스가 CORS 필터를 실제로
 * 연결하는 순간부터는 오리진 허용 목록을 정확히 채워야 한다 — 그렇지 않으면 프론트가 이미 보내고
 * 있는 요청까지 막힌다(아래 "same-origin 오판" 문단 참고).
 *
 * same-origin 요청도 잘못 막힐 수 있다는 점(리뷰 지적 P1) — Spring의 {@code CorsUtils.isCorsRequest()}는
 * Origin 헤더의 scheme·host·port를 요청 자신의 scheme·host·port와 비교해 하나라도 다르면
 * cross-origin으로 판정한다. 로컬 개발은 프론트(5173)와 백엔드(8080) 포트가 실제로 달라 이
 * allowedOrigins에 정확히 등록돼 있어야만 통과하고(application.yaml의 cors.allowed-origins 기본값
 * 참고), 운영 배포는 nginx가 TLS를 종단하면서 Spring이 자신의 scheme을 내부 http로 오인하면
 * (server.forward-headers-strategy 미설정 시) 실제로는 같은 오리진인 요청까지 cross-origin으로
 * 오판해 403이 난다 — 그래서 forward-headers-strategy: framework를 함께 설정해야 한다.
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
 * allowedOrigins가 빈 리스트면 어떤 오리진도 허용하지 않는다(fail-closed) — 다만 이건
 * "Origin이 있는 요청에는 아무 영향이 없다"는 뜻이 아니다(위 same-origin 오판 문단 참고).
 * 정확히는 Spring이 cross-origin으로 판정한 요청(Origin이 요청 자신의 scheme·host·port와
 * 다른 경우)만 이 빈 리스트의 영향을 받아 거부되고, Origin 헤더가 아예 없는 요청이나 Spring이
 * same-origin으로 판정한 요청에는 영향이 없다. application.yaml의 cors.allowed-origins
 * 기본값은 그래서 완전히 비어있지 않고 로컬 프론트 dev 오리진을 채워둔다.
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
