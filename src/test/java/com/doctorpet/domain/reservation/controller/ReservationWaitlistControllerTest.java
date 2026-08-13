package com.doctorpet.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.reservation.dto.response.ReservationWaitlistResponse;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.service.ReservationWaitlistService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ReservationWaitlistController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class ReservationWaitlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationWaitlistService reservationWaitlistService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @Test
    @DisplayName("보호자는 마감 슬롯 대기열에 등록할 수 있다")
    void register_guardian_returnsCreated() throws Exception {
        given(reservationWaitlistService.register(eq(1L), eq(10L))).willReturn(
                new ReservationWaitlistResponse(
                        20L,
                        10L,
                        ReservationWaitlistStatus.WAITING,
                        LocalDateTime.of(2026, 8, 13, 10, 0)
                )
        );

        mockMvc.perform(post("/api/reservation-waitlists")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRequest(10L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.waitlistId").value(20L))
                .andExpect(jsonPath("$.data.status").value("WAITING"));

        verify(reservationWaitlistService).register(1L, 10L);
    }

    @Test
    @DisplayName("slotId가 없으면 대기열 등록 요청을 거부한다")
    void register_missingSlotId_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/reservation-waitlists")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private Authentication memberAuthentication(Long memberId, String role) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@test.com", role);
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    private record CreateRequest(Long slotId) {
    }
}
