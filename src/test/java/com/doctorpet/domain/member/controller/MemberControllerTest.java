package com.doctorpet.domain.member.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Level 2 — API 계약(상태코드·ApiResponse 포맷) 검증.
 * Security 필터 체인은 이 슬라이스 테스트의 관심사가 아니므로 addFilters=false로 끄고,
 * 실제 필터가 채우는 SecurityContext(MemberPrincipal)는 테스트에서 직접 주입한다.
 */
@WebMvcTest(controllers = MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberService memberService;

    @Test
    @DisplayName("내 정보 조회 성공 시 200과 회원 정보를 반환한다")
    void getMyInfo_success() throws Exception {
        MemberResponse response = new MemberResponse(1L, "guardian@example.com", "보호자닉네임", MemberRole.GUARDIAN, null);
        given(memberService.getMyInfo(1L)).willReturn(response);

        mockMvc.perform(get("/api/members/me")
                        .with(authentication(memberAuthentication(1L))))
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
        given(memberService.getMyInfo(anyLong()))
                .willThrow(new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/api/members/me")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_006"));
    }

    private Authentication memberAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
