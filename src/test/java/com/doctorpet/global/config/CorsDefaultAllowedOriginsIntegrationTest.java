package com.doctorpet.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * application.yaml의 실제 cors.allowed-origins 기본값(CORS_ALLOWED_ORIGINS 환경변수가 없을 때
 * {@code http://localhost:5173,http://127.0.0.1:5173})이 그대로 두 개의 오리진으로 파싱돼
 * 필터 체인까지 정확히 반영되는지 검증한다(PR 리뷰 지적 P1 반영 — frontend/ dev 서버가 실제로
 * 5173 포트에서 이 API를 호출하므로, 이 기본값이 로컬 개발에서 실제로 동작해야 한다).
 *
 * 다른 CORS 테스트와 달리 이 클래스는 {@code @TestPropertySource}로 cors.allowed-origins를
 * 덮어쓰지 않는다 — application.yaml의 진짜 기본값이 CI 환경(CORS_ALLOWED_ORIGINS 미설정)에서
 * 그대로 적용되는지를 봐야 하기 때문이다.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class CorsDefaultAllowedOriginsIntegrationTest {

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
    @DisplayName("기본값(환경변수 미설정)에서도 로컬 프론트 dev 오리진(5173)의 preflight는 허용된다")
    void preflight_localViteDevOrigin_allowedByDefault() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", HttpMethod.POST.name()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("기본값(환경변수 미설정)에서도 127.0.0.1:5173 오리진의 preflight는 허용된다")
    void preflight_localViteDevLoopbackOrigin_allowedByDefault() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("Access-Control-Request-Method", HttpMethod.POST.name()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
    }

    @Test
    @DisplayName("기본값에 없는 다른 오리진은 여전히 거부된다")
    void preflight_unrelatedOrigin_stillRejectedByDefault() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", HttpMethod.POST.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
