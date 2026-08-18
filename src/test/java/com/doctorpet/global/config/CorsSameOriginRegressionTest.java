package com.doctorpet.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.controller.AuthController;
import com.doctorpet.domain.member.service.AuthRateLimiter;
import com.doctorpet.domain.member.service.AuthService;
import com.doctorpet.domain.member.service.EmailVerificationService;
import com.doctorpet.domain.member.service.PasswordResetService;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PR 리뷰 지적 P1 회귀 방지 — {@code cors.allowed-origins}가 비어 있어도(fail-closed 시나리오),
 * Origin이 요청 자신의 scheme·host·port와 정확히 같은 "진짜 same-origin" 요청까지 CORS로
 * 오판해 거부해서는 안 된다.
 *
 * Spring의 {@code CorsUtils.isCorsRequest()}는 Origin 헤더를 요청 자신의 scheme·host·port와
 * 비교해 하나라도 다르면 cross-origin으로 판정하는데, 이전 버전은 "허용 오리진이 비어 있으면
 * Origin 헤더 없는 요청에는 영향이 없다"고만 검증했지 "Origin이 있지만 실제로는 같은 오리진인
 * 요청"은 검증하지 않았다 — 그 결과 로컬 프론트 dev 서버(포트가 실제로 다름)·nginx가 TLS를
 * 종단하는 운영 환경(server.forward-headers-strategy 미설정 시 Spring이 scheme을 오인)에서
 * 진짜 same-origin 요청까지 403으로 막히는 회귀를 CI가 잡아내지 못했다. 이 클래스는 그 시나리오를
 * 직접 재현해 검증한다 — CorsConfig 정적 팩터리만 보는 CorsConfigTest로는 Spring의
 * isCorsRequest() 판정 자체를 검증할 수 없어 실제 필터 체인이 필요하다(CorsSecurityIntegrationTest와
 * 같은 이유).
 *
 * MockHttpServletRequest의 기본값(scheme=http, serverName=localhost, serverPort=80)에
 * 맞춰 Origin을 구성한다 — 이 프로젝트는 MockMvc 기본 요청 설정을 별도로 커스터마이즈하지 않는다.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=")
class CorsSameOriginRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @MockitoBean
    private AuthRateLimiter authRateLimiter;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort;

    @Test
    @DisplayName("허용 오리진이 비어 있어도, Origin이 요청 자신의 scheme·host·port와 같은 진짜 same-origin 요청은 CORS로 거부되지 않는다(리뷰 지적 P1 회귀 방지)")
    void sameOriginActualRequest_notRejectedByCorsEvenWithEmptyAllowedOrigins() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .header("Origin", "http://localhost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guardian@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("허용 오리진이 비어 있고 Origin이 실제로 다른 오리진이면 여전히 거부된다")
    void crossOriginActualRequest_stillRejectedWithEmptyAllowedOrigins() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"guardian@example.com\"}"))
                .andExpect(status().isForbidden());
    }
}
