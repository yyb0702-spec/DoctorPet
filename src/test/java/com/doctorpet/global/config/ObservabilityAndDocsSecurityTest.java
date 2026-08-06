package com.doctorpet.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.doctorpet.domain.member.controller.MemberController;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Level 2 — 이슈 #105 완료 조건("액추에이터·Swagger 경로가 실제 SecurityConfig에서 인증 없이
 * 통과하는지")을 검증한다.
 *
 * management.server.port로 액추에이터를 앱 포트와 분리했지만, 그것만으로 Spring Security가
 * 자동으로 인증을 면제해주지는 않는다 — {@link SecurityConfig#actuatorSecurityFilterChain}처럼
 * {@code securityMatcher}로 범위를 좁힌 전용 체인을 명시적으로 permitAll해야 한다. 이 클래스는
 * 실제 {@link SecurityConfig}를 그대로 불러와({@code addFilters}를 끄지 않음) "/actuator/**"·
 * Swagger·{@code /healthz} 경로가 401/403 없이 통과하는지 MockMvc로 직접 확인한다 — 이 체인들이
 * 서로 다른 물리 포트에서 뜬다는 사실 자체는 여기서 검증할 수 없고(실제 HTTP 서버를 띄우지 않는
 * 슬라이스 테스트의 한계), springdoc이 실제로 등록돼 {@code /v3/api-docs}가 200을 반환하는지도
 * 여기서는 확인할 수 없다(슬라이스는 springdoc 자동구성을 로드하지 않으므로 401/403만 아니면
 * 통과 — 2차 리뷰 지적). 이 클래스가 검증하는 것은 "SecurityConfig의 인가 규칙이 이 경로들을
 * 막지 않는다"까지다. 관리 포트 8081의 health/prometheus 200, 앱 포트 8080의 /healthz 200,
 * local 프로파일의 /v3/api-docs 200, docker/prod 프로파일의 /v3/api-docs 404, Dockerfile
 * HEALTHCHECK의 실제 healthy 전환은 docker compose로 실제 기동해 Level 5·6으로 검증하고 PR
 * 본문에 기록한다(docs/testing/verification-guide.md).
 */
@WebMvcTest(controllers = MemberController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class ObservabilityAndDocsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private MemberWithdrawalApplicationService memberWithdrawalApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // SecurityConfig의 filterChain() 빈 생성 자체에 필요하다(MemberControllerSecurityTest와 동일 이유).
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @Test
    @DisplayName("/actuator/** 경로는 인증 헤더 없이도 401/403을 받지 않는다(전용 SecurityFilterChain, 이슈 #105)")
    void actuatorPaths_areNotBlockedByAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    @DisplayName("Swagger/OpenAPI 문서 경로는 인증 헤더 없이도 401/403을 받지 않는다(이슈 #105)")
    void swaggerPaths_areNotBlockedByAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    /*
     * 2차 리뷰 지적 대응 — 관리 포트(8081) 헬스체크만으로는 앱 포트(8080)가 실제로 새 연결을
     * 받는지 확인할 수 없다. readiness 헬스 그룹을 application.yaml에서 additional-path로 이
     * 포트의 /healthz에도 노출했으므로, 이 경로도 인증 없이 통과해야 Dockerfile HEALTHCHECK가
     * 정상 동작한다.
     */
    @Test
    @DisplayName("/healthz(앱 포트 readiness 추가 경로)는 인증 헤더 없이도 401/403을 받지 않는다(2차 리뷰 지적, 이슈 #105)")
    void healthzPath_isNotBlockedByAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/healthz")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }
}
