package com.doctorpet.global.config;

import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtAuthenticationFilter;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    // CORS 허용 오리진(기능 구멍 점검 대응, CorsConfig 참고)을 읽는 데 쓴다 — @Value가 아니라
    // Environment.getProperty()를 직접 호출하는 이유는 아래 parseAllowedOrigins() 주석 참고.
    private final Environment environment;

    // (PR 4차 리뷰 이후 CI `integrationTest`에서 실측으로 드러난 문제 수정 — 2차 시도) 처음엔
    // `@Value("${cors.allowed-origins:}") private List<String>`로 선언했다가, 콤마로 구분된
    // 오리진(예: `http://localhost:5173,http://127.0.0.1:5173`)이 두 개로 쪼개지지 않는 걸로
    // 의심해 `@Value(...) private String`로 받아 직접 split하도록 고쳤는데도 CI에서 정확히
    // 같은 두 preflight 테스트가 여전히 403으로 실패했다(`CorsDefaultAllowedOriginsIntegrationTest`,
    // `.env.example`/`CORS_ALLOWED_ORIGINS` 환경변수를 아무도 안 준 기본값 시나리오만 해당 —
    // `CorsSameOriginRegressionTest`처럼 `@TestPropertySource`로 값을 명시적으로 덮어쓴
    // 테스트는 두 시도 모두에서 항상 정상 동작했다). split 로직을 바꿔도 결과가 전혀 안 바뀐
    // 것은 애초에 `@Value`의 임베디드 값 해석 단계에서 이 프로퍼티의 값 자체가 기대한 문자열로
    // 안 들어오고 있다는 뜻으로 보고, `@Value`의 SpEL 기반 해석에 기대는 대신 Spring이 실제
    // 부팅 시(이 값이 정확히 동작함을 curl로 직접 확인한 real bootRun 포함) 쓰는 것과 같은
    // `Environment.getProperty()`로 직접 읽도록 바꿨다 — 두 메커니즘이 같은 PropertySource
    // 체계를 쓰더라도 임베디드 값 해석 경로가 다르므로, 특정 슬라이스 테스트 컨텍스트에서만
    // 발생하는 해석 차이가 있다면 이쪽이 더 신뢰할 수 있는 경로다.
    private static List<String> parseAllowedOrigins(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /*
     * 액추에이터 전용 체인(이슈 #105) — health/prometheus는 management.server.port(8081, 앱
     * 포트와 분리)로 옮겼지만, 별도 포트라고 해서 Spring Security가 자동으로 인증을 면제해주지
     * 않는다(Boot 문서·이슈 트래커에 실제로 자주 나오는 함정). securityMatcher로 이 체인을
     * "/actuator/**"에만 적용해 명시적으로 permitAll한다 — 그렇지 않으면 Dockerfile의
     * HEALTHCHECK(인증 없는 curl)가 401을 받아 컨테이너가 계속 unhealthy로 판정되고, 배포
     * 파이프라인이 정상 배포를 실패로 오판해 롤백을 반복하게 된다. 8081을 docker-compose가
     * 호스트에 게시하지 않는 것(mysql·redis와 동일 패턴)이 실질적인 방어선이고, 이 permitAll은
     * "같은 네트워크 안에서는 인증 없이도 열려야 하는 지표·헬스체크"라는 의도를 코드로 명확히
     * 남기기 위함이다. @Order(1)로 아래 메인 체인보다 먼저 평가되게 한다.
     *
     * 이 permitAll이 실제로 관리 포트(8081) 요청에도 적용되는지는 문서만으로 확정하기 어려운
     * 부분이라 Level 6(docker compose 실기동)로 확인했다(2차 리뷰 지적) — securityMatcher는
     * 포트가 아니라 경로로 매칭되고, FilterChainProxy는 포트별로 분리돼 있지 않아 관리 포트
     * 요청도 같은 체인들을 거친다(관리 포트 전용 DispatcherServlet은 별도 자식 컨텍스트라 실제
     * 라우팅만 분리됨, spring-projects/spring-boot#50355). 실제로 8081의 /actuator/health
     * 응답에 Spring Security의 HeaderWriterFilter가 남기는 표준 헤더(X-Frame-Options 등)가
     * 그대로 포함되는 것으로 이 체인이 관리 포트 요청에도 적용됨을 확인했다 — 즉 permitAll은
     * 문서용 의도 표시가 아니라 실제로 유효한 인가 규칙이고, docker-compose의 포트 비공개는
     * 그 위에 얹는 추가 방어선이다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // 브라우저 프론트엔드용 CORS(기능 구멍 점검 대응) — parseAllowedOrigins가 빈
                // 문자열을 빈 리스트로 바꾸므로, 미설정 시 어떤 오리진도 허용하지 않는다(fail-closed).
                .cors(cors -> cors.configurationSource(CorsConfig.corsConfigurationSource(
                        parseAllowedOrigins(environment.getProperty("cors.allowed-origins", "")))))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Swagger UI/OpenAPI 문서(이슈 #105) - local 프로파일에서만 springdoc이
                        // 실제로 등록되고(application.yaml), 그 외에는 springdoc.api-docs/
                        // swagger-ui.enabled=false라 경로 자체가 없어 여기서 permitAll을 열어둬도
                        // 404만 날 뿐 정보가 노출되지 않는다.
                        .requestMatchers(
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"
                        ).permitAll()
                        // 앱 포트(8080) 자체의 헬스체크(2차 리뷰 지적, 이슈 #105) - 관리 포트(8081)
                        // 헬스체크만으로는 관리 컨텍스트는 살아있지만 정작 메인 포트가 새 연결을
                        // 못 받는 상태를 놓칠 수 있다. readiness 헬스 그룹을 application.yaml에서
                        // additional-path(server:/healthz)로 이 포트에도 노출했으므로, 이 경로도
                        // /actuator/**와 마찬가지로 인증 없이 열어야 Dockerfile HEALTHCHECK가
                        // 401로 오판하지 않는다.
                        .requestMatchers(HttpMethod.GET, "/healthz").permitAll()
                        // 채팅 WebSocket은 HTTP Upgrade 단계에서는 STOMP CONNECT까지 통과시킨 뒤,
                        // ChatChannelInterceptor가 CONNECT Authorization 헤더로 인증을 강제한다.
                        .requestMatchers(HttpMethod.GET, "/ws/chat").permitAll()
                        // 인증/재발급 - 정책상 비인증 API (정책 결정 사항 §1, API 명세서 §1 참고)
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/signup", "/api/auth/login", "/api/auth/reissue"
                        ).permitAll()
                        // 이메일 인증·비밀번호 재설정(백로그 P2) - 로그인 전(또는 로그인 자체가
                        // 불가능한 상태의) 사용자가 호출해야 하므로 비인증 API다.
                        .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/verify-email/resend",
                                "/api/auth/password-reset/request",
                                "/api/auth/password-reset/confirm"
                        ).permitAll()
                        // 병원 검색·상세 - 공개. 회원별 찜 상태는 응답 조립 시 별도로 결합한다.
                        .requestMatchers(HttpMethod.GET, "/api/hospitals/**").permitAll()
                        // 병원 찜 등록·해제와 내 찜 목록 - 보호자 전용 (SA §8-3)
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/hospitals/*/favorite"
                        ).hasRole("GUARDIAN")
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/hospitals/*/favorite"
                        ).hasRole("GUARDIAN")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/members/me/favorite-hospitals"
                        ).hasRole("GUARDIAN")
                        // AI 상담 - 공개, 비로그인 임시 상담 허용 (API 명세서 §4)
                        .requestMatchers(HttpMethod.POST, "/api/ai/consultations").permitAll()
                        // 결제 웹훅 - JWT가 아니라 웹훅 서명으로 검증한다(이슈 #48). 컨트롤러가 서명 실패를 401로 거부한다.
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()
                        // 실시간 알림 SSE 구독 - EventSource가 JWT 헤더를 못 실으므로 비인증 경로로 열고,
                        // 발급받은 1회성 티켓(?ticket=)으로 서비스단에서 식별한다(SA §9-8). 티켓 발급 자체는 인증 필요.
                        .requestMatchers(HttpMethod.GET, "/api/notifications/subscribe").permitAll()
                        // 보호자 예약 요청·취소 (SA §8-5)
                        .requestMatchers(HttpMethod.POST, "/api/reservations").hasRole("GUARDIAN")
                        .requestMatchers(HttpMethod.POST, "/api/reservations/*/reviews")
                        .hasRole("GUARDIAN")
                        .requestMatchers(HttpMethod.PUT, "/api/reviews/*")
                        .hasRole("GUARDIAN")
                        .requestMatchers(HttpMethod.DELETE, "/api/reviews/*")
                        .hasRole("GUARDIAN")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/reservations",
                                "/api/reservations/*"
                        ).hasRole("GUARDIAN")
                        // 보호자 결제 내역 조회 (SA §8-7, 이슈 #47). 본인 예약 여부는 서비스에서 재검증한다.
                        .requestMatchers(HttpMethod.GET, "/api/reservations/*/payments").hasRole("GUARDIAN")
                        // 보호자 결제 실패 셀프 복구(다시 결제, 고도화 3.3). GET /payments 매처는 GET만 가드하므로
                        // 이 POST 경로를 명시 매처로 두지 않으면 anyRequest().authenticated()로 떨어져 스태프도 호출 가능해진다.
                        .requestMatchers(HttpMethod.POST, "/api/reservations/*/payments/recharge").hasRole("GUARDIAN")
                        // 보호자 JSON 영수증 조회 (SA §9-4 영수증). 본인 결제 여부는 서비스에서 재검증한다.
                        // POST /api/payments/webhook(permitAll)과 경로 접두사를 공유하므로 GET·하위 경로로 좁혀 매칭한다.
                        .requestMatchers(HttpMethod.GET, "/api/payments/*/receipt").hasRole("GUARDIAN")
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/reservations/*/cancel",
                                "/api/reservations/*/payment-method"
                        ).hasRole("GUARDIAN")
                        // 대기열 등록·조회·응답·취소는 보호자 본인의 예약 기회만 다룬다.
                        .requestMatchers("/api/reservation-waitlists/**").hasRole("GUARDIAN")
                        // 병원 예약 운영 API - 병원 스태프 전용 (SA §8-6)
                        .requestMatchers("/api/hospital/**")
                        .hasRole("HOSPITAL_STAFF")
                        // 결제수단 등록·조회·삭제 - 보호자 전용 (이슈 #33)
                        .requestMatchers("/api/payment-methods/**").hasRole("GUARDIAN")
                        // 반려동물 프로필 등록·조회·수정·삭제 - 보호자 전용 (SA §8-2)
                        .requestMatchers("/api/pets/**").hasRole("GUARDIAN")
                        // 진료비 청구(POST /api/hospital/reservations/*/payments)는 위
                        // /api/hospital/** 규칙이 이미 HOSPITAL_STAFF로 가드한다(이슈 #34·#74).
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
