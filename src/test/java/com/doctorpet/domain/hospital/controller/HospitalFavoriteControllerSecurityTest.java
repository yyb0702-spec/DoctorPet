package com.doctorpet.domain.hospital.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.hospital.dto.response.FavoriteHospitalPageResponse;
import com.doctorpet.domain.hospital.service.HospitalDetailApplicationService;
import com.doctorpet.domain.hospital.service.HospitalFavoriteService;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.hospital.service.HospitalSlotApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
        HospitalController.class,
        MemberFavoriteHospitalController.class
})
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class HospitalFavoriteControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalService hospitalService;

    @MockitoBean
    private HospitalDetailApplicationService hospitalDetailApplicationService;

    @MockitoBean
    private HospitalFavoriteService hospitalFavoriteService;

    @MockitoBean
    private HospitalSlotApplicationService hospitalSlotApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @Test
    void 비로그인_사용자는_병원을_찜할_수_없다() throws Exception {
        mockMvc.perform(put("/api/hospitals/10/favorite"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    void 병원_스태프는_병원을_찜할_수_없다() throws Exception {
        stubToken("staff-token", "HOSPITAL_STAFF");

        mockMvc.perform(put("/api/hospitals/10/favorite")
                        .header("Authorization", "Bearer staff-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    void 보호자는_병원을_찜할_수_있다() throws Exception {
        stubToken("guardian-token", "GUARDIAN");

        mockMvc.perform(put("/api/hospitals/10/favorite")
                        .header("Authorization", "Bearer guardian-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void 비로그인_사용자는_병원_찜을_해제할_수_없다() throws Exception {
        mockMvc.perform(delete("/api/hospitals/10/favorite"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    void 병원_스태프는_병원_찜을_해제할_수_없다() throws Exception {
        stubToken("staff-token", "HOSPITAL_STAFF");

        mockMvc.perform(delete("/api/hospitals/10/favorite")
                        .header("Authorization", "Bearer staff-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    void 보호자는_병원_찜을_해제할_수_있다() throws Exception {
        stubToken("guardian-token", "GUARDIAN");

        mockMvc.perform(delete("/api/hospitals/10/favorite")
                        .header("Authorization", "Bearer guardian-token"))
                .andExpect(status().isNoContent());

        verify(hospitalFavoriteService).removeFavorite(1L, 10L);
    }

    @Test
    void 비로그인_사용자는_내_찜_목록을_조회할_수_없다()
            throws Exception {
        mockMvc.perform(get("/api/members/me/favorite-hospitals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    void 보호자는_내_찜_목록을_조회할_수_있다() throws Exception {
        stubToken("guardian-token", "GUARDIAN");
        given(hospitalFavoriteService.getMyFavorites(1L, 1, 20))
                .willReturn(new FavoriteHospitalPageResponse(
                        List.of(),
                        1,
                        20,
                        0,
                        0,
                        true,
                        true
                ));

        mockMvc.perform(get("/api/members/me/favorite-hospitals")
                        .header("Authorization", "Bearer guardian-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    private void stubToken(String token, String role) {
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.getTokenType(token))
                .willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(token))
                .willReturn(new MemberPrincipal(
                        1L,
                        "member@example.com",
                        role
                ));
    }
}
