package com.doctorpet.domain.reservation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.doctorpet.domain.reservation.service.HospitalReservationApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = HospitalReservationController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalReservationAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HospitalReservationApplicationService hospitalReservationApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("비인증 사용자는 병원 예약 운영 API에 접근할 수 없다")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/hospital/reservations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("보호자는 병원 예약 운영 API에 접근할 수 없다")
    void guardian_isForbidden() throws Exception {
        mockMvc.perform(get("/api/hospital/reservations")
                        .with(authentication(guardianAuthentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("병원 스태프는 자기 병원의 예약 요청 목록에 접근할 수 있다")
    void hospitalStaff_isAllowed() throws Exception {
        given(hospitalReservationApplicationService.findHospitalReservations(
                50L,
                "REQUESTED",
                0,
                20
        )).willReturn(Page.empty());

        mockMvc.perform(get("/api/hospital/reservations")
                        .with(authentication(memberAuthentication(
                                50L,
                                "HOSPITAL_STAFF"
                        ))))
                .andExpect(status().isOk());

        verify(hospitalReservationApplicationService).findHospitalReservations(
                50L,
                "REQUESTED",
                0,
                20
        );
    }

    @Test
    @DisplayName("정책에 없는 거절 사유는 400으로 거부한다")
    void reject_withUnsupportedReason_returnsBadRequest() throws Exception {
        mockMvc.perform(patch(
                                "/api/hospital/reservations/{reservationId}/reject",
                                10L
                        )
                        .with(authentication(memberAuthentication(
                                50L,
                                "HOSPITAL_STAFF"
                        )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UnsupportedRejectReason("진료 슬롯 부족")
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private Authentication guardianAuthentication() {
        return memberAuthentication(1L, "GUARDIAN");
    }

    private Authentication memberAuthentication(Long memberId, String role) {
        MemberPrincipal principal = new MemberPrincipal(
                memberId,
                role.toLowerCase() + "@example.com",
                role
        );
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    private record UnsupportedRejectReason(String rejectReason) {
    }
}
