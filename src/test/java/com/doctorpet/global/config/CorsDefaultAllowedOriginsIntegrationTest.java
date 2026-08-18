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
 * cors.allowed-origins 기본값(CORS_ALLOWED_ORIGINS 환경변수가 없을 때
 * {@code http://localhost:5173,http://127.0.0.1:5173})이 그대로 두 개의 오리진으로 파싱돼
 * 필터 체인까지 정확히 반영되는지 검증한다(PR 리뷰 지적 P1 반영 — frontend/ dev 서버가 실제로
 * 5173 포트에서 이 API를 호출하므로, 이 기본값이 로컬 개발에서 실제로 동작해야 한다).
 *
 * 다른 CORS 테스트와 달리 이 클래스는 {@code @TestPropertySource}로 cors.allowed-origins를
 * 덮어쓰지 않는다 — CORS_ALLOWED_ORIGINS 환경변수가 없을 때의 기본 동작을 봐야 하기 때문이다.
 * 주의: 테스트 실행 시 클래스패스에서는 src/main/resources/application.yaml이 아니라
 * src/test/resources/application.yaml이 로드된다(Spring Boot가 classpath:/application.yaml을
 * 단일 리소스로 찾아, 먼저 매치되는 test 쪽이 main 쪽을 가린다 — 이 파일 상단 주석 참고). 그래서
 * 이 테스트가 실제로 검증하는 기본값은 src/test/resources/application.yaml에 main과 동일하게
 * 복제해 둔 cors.allowed-origins 플레이스홀더다(이슈 #181, CI 4차 실패로 이 복제 누락이
 * 드러남 — raw=[]로 로딩돼 정상 오리진까지 403으로 거부됐었다). 두 파일의 기본값이 어긋나면
 * 이 테스트가 검증하는 것과 실제 앱 동작이 달라지므로, cors.allowed-origins를 바꿀 때는 항상
 * 두 application.yaml을 함께 수정해야 한다.
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
