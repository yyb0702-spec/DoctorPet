package com.doctorpet.domain.reservation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.doctorpet.domain.reservation.service.ReservationApplicationService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ReservationController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalReservationAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationApplicationService reservationApplicationService;

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

    private Authentication guardianAuthentication() {
        MemberPrincipal principal = new MemberPrincipal(
                1L,
                "guardian@example.com",
                "GUARDIAN"
        );
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN"))
        );
    }
}
