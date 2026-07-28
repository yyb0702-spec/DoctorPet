package com.doctorpet.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ReservationController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("보호자는 예약을 요청할 수 있다")
    void request_guardian_returnsCreated() throws Exception {
        ReservationRequest request = validRequest();
        given(reservationService.request(eq(1L), any(ReservationRequest.class)))
                .willReturn(new ReservationResponse(
                        10L,
                        2L,
                        3L,
                        4L,
                        ReservationStatus.REQUESTED,
                        LocalDateTime.now()
                ));

        mockMvc.perform(post("/api/reservations")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reservationId").value(10L));
    }

    @Test
    @DisplayName("병원 스태프는 보호자 예약을 요청할 수 없다")
    void request_hospitalStaff_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .with(authentication(memberAuthentication(1L, "HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("미인증 사용자는 예약을 요청할 수 없다")
    void request_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("필수 ID가 누락되면 400을 반환한다")
    void request_missingSlotId_returnsBadRequest() throws Exception {
        ReservationRequest request = new ReservationRequest(
                2L,
                null,
                5L,
                "초코",
                "DOG"
        );

        mockMvc.perform(post("/api/reservations")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("ID가 0 이하이면 400을 반환한다")
    void request_nonPositiveId_returnsBadRequest() throws Exception {
        ReservationRequest request = new ReservationRequest(
                -1L,
                4L,
                5L,
                "초코",
                "DOG"
        );

        mockMvc.perform(post("/api/reservations")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("보호자는 본인 예약을 취소할 수 있다")
    void cancel_guardian_returnsSuccess() throws Exception {
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 10L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(reservationService).cancel(1L, 10L);
    }

    @Test
    @DisplayName("병원 스태프는 보호자 예약을 취소할 수 없다")
    void cancel_hospitalStaff_returnsForbidden() throws Exception {
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 10L)
                        .with(authentication(memberAuthentication(1L, "HOSPITAL_STAFF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    private ReservationRequest validRequest() {
        return new ReservationRequest(2L, 4L, 5L, "초코", "DOG");
    }

    private Authentication memberAuthentication(Long memberId, String role) {
        MemberPrincipal principal = new MemberPrincipal(
                memberId,
                "guardian@example.com",
                role
        );
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
