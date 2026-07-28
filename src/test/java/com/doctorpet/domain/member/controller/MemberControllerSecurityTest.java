package com.doctorpet.domain.member.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

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
                .willReturn(new MemberResponse(1L, "guardian@example.com", "보호자닉네임", MemberRole.GUARDIAN, null));

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
}
