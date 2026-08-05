package com.doctorpet.domain.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.dto.request.EmailRequest;
import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.PasswordResetConfirmRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.AuthService;
import com.doctorpet.domain.member.service.EmailVerificationService;
import com.doctorpet.domain.member.service.PasswordResetService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 2 — API 계약(상태코드·ApiResponse 포맷·Validation) 검증.
 * Security 필터 체인은 이 슬라이스 테스트의 관심사가 아니므로 addFilters=false로 끈다.
 * GlobalExceptionHandler(@RestControllerAdvice)는 @WebMvcTest가 자동으로 인식한다.
 *
 * SecurityConfig를 명시적으로 @Import하는 이유: @WebMvcTest는 @Configuration 클래스(SecurityConfig)를
 * 슬라이스에서 제외한다. SecurityFilterChain 빈이 하나도 없으면 Boot의 WebSecurityEnablerConfiguration이
 * @EnableWebSecurity를 트리거하지 않고, 그 결과 WebMvcSecurityConfiguration도 로드되지 않아
 * AuthenticationPrincipalArgumentResolver 자체가 등록되지 않는다. JwtAccessDeniedHandler·
 * JwtAuthenticationEntryPoint는 SecurityConfig 생성자가 요구해서 함께 import한다.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    // addFilters=false는 MockMvc가 필터를 "실행"하지 않게 할 뿐, @WebMvcTest는 Filter 타입 빈을
    // 기본 포함 대상으로 슬라이스 컨텍스트에 여전히 생성한다. JwtAuthenticationFilter가 생성자에서
    // JwtTokenProvider·MemberBlacklistPort·AccessTokenBlacklistPort를 요구하므로, 이 빈들이 없으면
    // 컨텍스트 로딩 자체가 실패한다(실제 호출은 없다). MemberBlacklistPort는 탈퇴 회원(리뷰 지적
    // P1 대응), AccessTokenBlacklistPort는 로그아웃한 토큰(#124) Access Token 블랙리스트 체크용.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("회원가입 성공 시 201과 memberId를 반환한다")
    void signup_success() throws Exception {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "보호자닉네임");
        given(authService.signup(any(SignupRequest.class))).willReturn(new SignupResponse(1L));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.memberId").value(1));
    }

    @Test
    @DisplayName("이메일이 중복이면 409와 MEMBER_001을 반환한다")
    void signup_duplicateEmail() throws Exception {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "보호자닉네임");
        given(authService.signup(any(SignupRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.DUPLICATE_EMAIL));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_001"));
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 400과 COMMON_001을 반환한다")
    void signup_invalidEmail() throws Exception {
        SignupRequest request = new SignupRequest("not-an-email", "password1234", "보호자닉네임");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("비밀번호가 8자 미만이면 400과 COMMON_001을 반환한다")
    void signup_shortPassword() throws Exception {
        SignupRequest request = new SignupRequest("guardian@example.com", "short", "보호자닉네임");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("비밀번호가 72자를 초과하면 400과 COMMON_001을 반환한다")
    void signup_longPassword() throws Exception {
        SignupRequest request = new SignupRequest("guardian@example.com", "a".repeat(73), "보호자닉네임");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("비밀번호 문자 수는 72 이하지만 UTF-8 바이트 수가 BCrypt 한도(72)를 넘는 한글 비밀번호는 400과 COMMON_001을 반환한다")
    void signup_passwordExceedsUtf8ByteLimit() throws Exception {
        // 한글 25자 = UTF-8 75바이트. 문자 수(25)만 보면 통과할 것 같지만 바이트 수는 초과한다 —
        // @Size(max = 72)였다면 이 요청이 통과해 passwordEncoder.encode()에서 500이 났을 케이스(리뷰 지적).
        String longKoreanPassword = "가".repeat(25);
        SignupRequest request = new SignupRequest("guardian@example.com", longKoreanPassword, "보호자닉네임");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("닉네임이 255자를 초과하면 400과 COMMON_001을 반환한다(DB 길이 초과로 500이 나던 버그 수정)")
    void signup_longNickname() throws Exception {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "닉".repeat(256));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("이메일이 255자를 초과하면 400과 COMMON_001을 반환한다")
    void signup_longEmail() throws Exception {
        String longLocalPart = "a".repeat(250);
        SignupRequest request = new SignupRequest(longLocalPart + "@example.com", "password1234", "보호자닉네임");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("로그인 성공 시 200과 토큰 쌍을 반환한다")
    void login_success() throws Exception {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        given(authService.login(any(LoginRequest.class)))
                .willReturn(new LoginResponse("access-token", "refresh-token"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
    }

    @Test
    @DisplayName("이메일·비밀번호가 틀리면 401과 MEMBER_002를 반환한다")
    void login_invalidCredentials() throws Exception {
        LoginRequest request = new LoginRequest("guardian@example.com", "wrong-password");
        given(authService.login(any(LoginRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MEMBER_002"));
    }

    @Test
    @DisplayName("계정이 잠겨 있으면 401과 MEMBER_003을 반환한다")
    void login_accountLocked() throws Exception {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        given(authService.login(any(LoginRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.ACCOUNT_LOCKED));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MEMBER_003"));
    }

    @Test
    @DisplayName("이메일 인증 전이면 403과 MEMBER_009를 반환한다(백로그 P2)")
    void login_emailNotVerified() throws Exception {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        given(authService.login(any(LoginRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.EMAIL_NOT_VERIFIED));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_009"));
    }

    @Test
    @DisplayName("로그인 요청에 비밀번호가 비어있으면 400과 COMMON_001을 반환한다")
    void login_blankPassword() throws Exception {
        LoginRequest request = new LoginRequest("guardian@example.com", "");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("재발급 성공 시 200과 새 토큰 쌍을 반환한다")
    void reissue_success() throws Exception {
        ReissueRequest request = new ReissueRequest("old-refresh-token");
        given(authService.reissue(any(ReissueRequest.class)))
                .willReturn(new LoginResponse("new-access-token", "new-refresh-token"));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));
    }

    @Test
    @DisplayName("유효하지 않은 토큰이면 401과 MEMBER_004를 반환한다")
    void reissue_invalidToken() throws Exception {
        ReissueRequest request = new ReissueRequest("broken-token");
        given(authService.reissue(any(ReissueRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.INVALID_REFRESH_TOKEN));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MEMBER_004"));
    }

    @Test
    @DisplayName("이미 회전되어 폐기된 토큰이 재사용되면 401과 MEMBER_005를 반환한다")
    void reissue_tokenReused() throws Exception {
        ReissueRequest request = new ReissueRequest("already-rotated-token");
        given(authService.reissue(any(ReissueRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.REFRESH_TOKEN_REUSED));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MEMBER_005"));
    }

    @Test
    @DisplayName("같은 회원의 다른 재발급 요청이 이미 처리 중이면 409와 MEMBER_007을 반환한다")
    void reissue_alreadyInProgress() throws Exception {
        ReissueRequest request = new ReissueRequest("some-refresh-token");
        given(authService.reissue(any(ReissueRequest.class)))
                .willThrow(new ServiceException(MemberErrorCode.REISSUE_IN_PROGRESS));

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_007"));
    }

    @Test
    @DisplayName("재발급 요청에 refreshToken이 비어있으면 400과 COMMON_001을 반환한다")
    void reissue_blankToken() throws Exception {
        ReissueRequest request = new ReissueRequest("");

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("로그아웃하면 200과 SUCCESS를 반환하고, 인증된 회원 id와 Authorization 헤더의 Access Token으로 서비스를 호출한다(#124)")
    void logout_success() throws Exception {

        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(authService).logout(1L, "access-token");
    }

    @Test
    @DisplayName("Authorization 헤더 없이 로그아웃해도(방어적 케이스) 200을 반환하고, accessToken은 null로 서비스를 호출한다")
    void logout_withoutAuthorizationHeader() throws Exception {

        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(authService).logout(1L, null);
    }

    @Test
    @DisplayName("이메일 인증 성공 시 200과 SUCCESS를 반환한다")
    void verifyEmail_success() throws Exception {
        mockMvc.perform(get("/api/auth/verify-email").param("token", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(emailVerificationService).verifyEmail("valid-token");
    }

    @Test
    @DisplayName("이메일 인증 토큰이 유효하지 않거나 만료됐으면 400과 MEMBER_010을 반환한다")
    void verifyEmail_invalidToken() throws Exception {
        willThrow(new ServiceException(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN))
                .given(emailVerificationService).verifyEmail(anyString());

        mockMvc.perform(get("/api/auth/verify-email").param("token", "bad-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_010"));
    }

    @Test
    @DisplayName("인증 메일 재발송 요청은 계정 존재 여부와 무관하게 항상 200과 SUCCESS를 반환한다")
    void resendVerificationEmail_alwaysSucceeds() throws Exception {
        EmailRequest request = new EmailRequest("guardian@example.com");

        mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(emailVerificationService).resendVerificationEmail("guardian@example.com");
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 재발송 요청도 400과 COMMON_001을 반환한다")
    void resendVerificationEmail_invalidEmail() throws Exception {
        EmailRequest request = new EmailRequest("not-an-email");

        mockMvc.perform(post("/api/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("비밀번호 재설정 요청은 계정 존재 여부와 무관하게 항상 200과 SUCCESS를 반환한다")
    void requestPasswordReset_alwaysSucceeds() throws Exception {
        EmailRequest request = new EmailRequest("guardian@example.com");

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(passwordResetService).requestPasswordReset("guardian@example.com");
    }

    @Test
    @DisplayName("비밀번호 재설정 확인 성공 시 200과 SUCCESS를 반환한다")
    void confirmPasswordReset_success() throws Exception {
        PasswordResetConfirmRequest request = new PasswordResetConfirmRequest("valid-token", "newPassword1234");

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(passwordResetService).confirmPasswordReset("valid-token", "newPassword1234");
    }

    @Test
    @DisplayName("비밀번호 재설정 토큰이 유효하지 않거나 만료됐으면 400과 MEMBER_010을 반환한다")
    void confirmPasswordReset_invalidToken() throws Exception {
        PasswordResetConfirmRequest request = new PasswordResetConfirmRequest("bad-token", "newPassword1234");
        willThrow(new ServiceException(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN))
                .given(passwordResetService).confirmPasswordReset(anyString(), anyString());

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_010"));
    }

    @Test
    @DisplayName("재설정할 새 비밀번호가 8자 미만이면 400과 COMMON_001을 반환한다")
    void confirmPasswordReset_shortPassword() throws Exception {
        PasswordResetConfirmRequest request = new PasswordResetConfirmRequest("valid-token", "short");

        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private Authentication memberAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
