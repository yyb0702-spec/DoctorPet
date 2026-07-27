package com.doctorpet.domain.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.AuthService;
import com.doctorpet.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 2 — API 계약(상태코드·ApiResponse 포맷·Validation) 검증.
 * Security 필터 체인은 이 슬라이스 테스트의 관심사가 아니므로 addFilters=false로 끈다.
 * GlobalExceptionHandler(@RestControllerAdvice)는 @WebMvcTest가 자동으로 인식한다.
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

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
    @DisplayName("재발급 요청에 refreshToken이 비어있으면 400과 COMMON_001을 반환한다")
    void reissue_blankToken() throws Exception {
        ReissueRequest request = new ReissueRequest("");

        mockMvc.perform(post("/api/auth/reissue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }
}
