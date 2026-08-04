package com.doctorpet.domain.member.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.dto.request.NicknameUpdateRequest;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
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
 * Level 2 — API 계약(상태코드·ApiResponse 포맷) 검증.
 * Security 필터 체인 자체는 이 슬라이스 테스트의 관심사가 아니므로 addFilters=false로 끈다.
 * 다만 @WebMvcTest는 SecurityConfig(@Configuration)를 슬라이스에서 제외하므로, SecurityFilterChain 빈이
 * 없으면 WebSecurityEnablerConfiguration이 @EnableWebSecurity를 트리거하지 않아 @AuthenticationPrincipal
 * 인자 리졸버(AuthenticationPrincipalArgumentResolver)가 등록되지 않는다 — 그래서 SecurityConfig 등을
 * @Import로 명시적으로 끌어온다. 또한 SecurityMockMvcRequestPostProcessors.authentication(...)은
 * addFilters=false에서는 SecurityContextHolderFilter가 돌지 않아 SecurityContext에 반영되지 않으므로,
 * 테스트에서 SecurityContextHolder에 직접 주입한다.
 */
@WebMvcTest(controllers = MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private MemberWithdrawalApplicationService memberWithdrawalApplicationService;

    // @WebMvcTest는 Filter 타입 빈(JwtAuthenticationFilter)을 addFilters=false여도 컨텍스트에는 생성하므로,
    // 그 생성자 의존성인 JwtTokenProvider·MemberBlacklistPort가 없으면 NoSuchBeanDefinitionException으로
    // 컨텍스트 로딩이 실패한다(MemberBlacklistPort는 탈퇴 회원 Access Token 블랙리스트 체크용 — 리뷰 지적 P1 대응).
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("내 정보 조회 성공 시 200과 회원 정보를 반환한다")
    void getMyInfo_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        MemberResponse response = new MemberResponse(1L, "guardian@example.com", "보호자닉네임", MemberRole.GUARDIAN, null);
        given(memberService.getMyInfo(1L)).willReturn(response);

        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.memberId").value(1))
                .andExpect(jsonPath("$.data.email").value("guardian@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("보호자닉네임"))
                .andExpect(jsonPath("$.data.role").value("GUARDIAN"))
                .andExpect(jsonPath("$.data.hospitalId").value(nullValue()));
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원이 존재하지 않으면 404와 MEMBER_006을 반환한다")
    void getMyInfo_memberNotFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        given(memberService.getMyInfo(anyLong()))
                .willThrow(new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_006"));
    }

    @Test
    @DisplayName("닉네임 수정 성공 시 200과 변경된 회원 정보를 반환한다")
    void updateNickname_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        NicknameUpdateRequest request = new NicknameUpdateRequest("새닉네임");
        MemberResponse response = new MemberResponse(1L, "guardian@example.com", "새닉네임", MemberRole.GUARDIAN, null);
        given(memberService.updateNickname(1L, "새닉네임")).willReturn(response);

        mockMvc.perform(patch("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("새닉네임"));
    }

    @Test
    @DisplayName("닉네임이 비어있으면 400과 COMMON_001을 반환한다")
    void updateNickname_blankNickname() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        NicknameUpdateRequest request = new NicknameUpdateRequest("");

        mockMvc.perform(patch("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("닉네임 수정 시 토큰은 유효하지만 회원이 존재하지 않으면 404와 MEMBER_006을 반환한다")
    void updateNickname_memberNotFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        NicknameUpdateRequest request = new NicknameUpdateRequest("새닉네임");
        given(memberService.updateNickname(anyLong(), anyString()))
                .willThrow(new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(patch("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_006"));
    }

    @Test
    @DisplayName("탈퇴 성공 시 200을 반환한다")
    void withdraw_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));

        mockMvc.perform(delete("/api/members/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    @DisplayName("활성 예약이 있으면 409와 MEMBER_008을 반환한다")
    void withdraw_withdrawalBlocked() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(1L));
        willThrow(new ServiceException(MemberErrorCode.WITHDRAWAL_BLOCKED))
                .given(memberWithdrawalApplicationService).withdraw(anyLong());

        mockMvc.perform(delete("/api/members/me"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_008"));
    }

    private Authentication memberAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
