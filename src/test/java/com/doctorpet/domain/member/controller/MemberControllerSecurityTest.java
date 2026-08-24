package com.doctorpet.domain.member.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.dto.request.NicknameUpdateRequest;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 2 — 이슈 #44 테스트 체크리스트의 "비인증 요청 거부"·"민감 정보 미노출 검증" 완료 조건을
 * 검증하는 보안 계약 테스트.
 *
 * {@link MemberControllerTest}는 {@code addFilters = false}로 Security 필터 체인 자체를 꺼서
 * SecurityContext를 테스트가 직접 주입한다 — API 계약(상태코드·응답 바디)만 빠르게 검증하려는
 * 의도였지만, 그 결과 실제 필터·인가 규칙은 전혀 실행되지 않는다. 그래서 {@code /api/members/me}가
 * 실수로 {@code permitAll}로 바뀌어도 그 테스트는 통과해버린다(리뷰 지적). 이 클래스는 반대로
 * {@code addFilters}를 끄지 않고(기본값 true) 실제 {@link SecurityConfig}·
 * {@link com.doctorpet.global.security.JwtAuthenticationFilter}가 그대로 동작하게 둬서,
 * 인증 여부에 따른 실제 허용/차단 결과를 검증한다.
 */
@WebMvcTest(controllers = MemberController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class MemberControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private MemberWithdrawalApplicationService memberWithdrawalApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter가 탈퇴 회원 Access Token 블랙리스트를 확인하므로(리뷰 지적 P1 대응),
    // 이 빈이 없으면 SecurityConfig의 filterChain() 빈 생성 자체가 실패한다. 스텁하지 않으면
    // Mockito 기본값(false)이 반환돼 기존 테스트들의 동작에는 영향이 없다.
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @Test
    @DisplayName("Authorization 헤더 없이 요청하면 401을 반환한다 — /api/members/me가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void getMyInfo_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("서명이 유효하지 않은 토큰으로 요청하면 401을 반환한다")
    void getMyInfo_withInvalidToken_returnsUnauthorized() throws Exception {
        given(jwtTokenProvider.validateToken("invalid-token")).willReturn(false);

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("유효한 Access Token으로 요청하면 200과 본인 정보를 반환하고, password 등 민감 필드는 응답에 존재하지 않는다")
    void getMyInfo_success_doesNotExposeSensitiveFields() throws Exception {
        String accessToken = "valid-access-token";
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(memberService.getMyInfo(1L))
                .willReturn(new MemberResponse(1L, "guardian@example.com", "보호자닉네임", "010-1234-5678", MemberRole.GUARDIAN, null));

        mockMvc.perform(get("/api/members/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(1))
                .andExpect(jsonPath("$.data.email").value("guardian@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("보호자닉네임"))
                .andExpect(jsonPath("$.data.role").value("GUARDIAN"))
                // 민감 정보 미노출 검증(이슈 #44 완료 조건) — MemberResponse에 password 필드 자체가
                // 없지만, 앞으로 응답 DTO가 바뀌거나 엔티티를 직접 직렬화하는 실수를 방지하는
                // 회귀 가드 역할을 한다.
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.encodedPassword").doesNotExist());
    }

    @Test
    @DisplayName("Authorization 헤더 없이 닉네임 수정 요청하면 401을 반환한다 — /api/members/me PATCH가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void updateNickname_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        NicknameUpdateRequest request = new NicknameUpdateRequest("새닉네임");

        mockMvc.perform(patch("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 탈퇴 요청하면 401을 반환한다 — /api/members/me DELETE가 실수로 permitAll이 되면 이 테스트가 잡는다")
    void withdraw_withoutAuthorizationHeader_returnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    // PR #191 리뷰 P2 대응 — 기존 CorsSecurityIntegrationTest는 원래도 permitAll인
    // /api/auth/login으로만 preflight를 검증해서, SecurityConfig에 새로 추가한 전역
    // "OPTIONS /** permitAll" 규칙이 없어도 그대로 통과했다(그 규칙이 실제로 막아야 하는
    // 건 인증이 필요한 경로의 OPTIONS다). 위 테스트들이 쓰는 /api/members/me(GET은
    // anyRequest().authenticated()로 떨어짐, 명시적 permitAll 없음)로 preflight를 보내
    // JWT 없이도 통과하는지 확인해야 새 규칙 자체를 검증한 게 된다.
    @Test
    @DisplayName("허용된 Origin의 CORS 프리플라이트는 인증이 필요한 경로에도 인증 없이 200과 Access-Control-Allow-Origin을 반환한다 — OPTIONS permitAll이 없으면 401이 난다")
    void preflight_authenticatedPath_allowedOrigin_returnsOkWithAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/members/me")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    // Origin 헤더가 없으면 Spring의 CorsFilter는 아예 개입하지 않는다(CorsUtils.isCorsRequest가
    // false) — 그래서 위 테스트만으로는 CorsFilter의 short-circuit과 우리가 추가한
    // authorizeHttpRequests의 OPTIONS permitAll 매처를 구분하지 못한다. Origin 없이 순수하게
    // 인가 규칙만 태워 이 매처 자체가 동작하는지 별도로 확인한다.
    @Test
    @DisplayName("Origin 헤더 없는 일반 OPTIONS 요청도 인증 없이 401로 거부되지 않는다 — 전역 OPTIONS permitAll 매처 자체를 검증한다")
    void options_withoutOriginHeader_isNotBlockedByAuthorization() throws Exception {
        mockMvc.perform(options("/api/members/me"))
                .andExpect(status().isOk());
    }
}
