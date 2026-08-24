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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SecurityConfig가 실제로 CORS를 적용하는지 필터 체인까지 통째로 검증한다(기능 구멍 점검
 * 대응). CorsConfigTest는 CorsConfig 정적 팩터리만 스프링 없이 검증하므로, 이 클래스는 그
 * 결과물이 실제 Spring Security 필터 체인에 올바르게 연결되는지(@Value 주입 포함)를 확인한다.
 * addFilters=false를 쓰지 않는다 — CORS는 Spring Security의 CorsFilter가 실행돼야 헤더가
 * 붙으므로, 이 시나리오만큼은 필터 체인이 실제로 동작해야 한다(MemberControllerSecurityTest와
 * 같은 이유).
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=https://app.doctorpet.example")
class CorsSecurityIntegrationTest {

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
    @DisplayName("cors.allowed-origins에 등록된 오리진의 preflight 요청은 Access-Control-Allow-Origin을 그대로 돌려받는다")
    void preflight_allowedOrigin_returnsAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://app.doctorpet.example")
                        .header("Access-Control-Request-Method", HttpMethod.POST.name()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.doctorpet.example"));
    }

    @Test
    @DisplayName("허용되지 않은 오리진의 preflight 요청은 403으로 거부되고 Access-Control-Allow-Origin 헤더도 없다")
    void preflight_disallowedOrigin_rejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", HttpMethod.POST.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
