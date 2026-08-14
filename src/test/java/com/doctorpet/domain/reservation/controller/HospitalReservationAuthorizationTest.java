package com.doctorpet.domain.reservation.controller;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.doctorpet.domain.reservation.service.HospitalReservationApplicationService;
import com.doctorpet.domain.reservation.dto.response.ReservationCheckInResponse;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
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

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

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
    @DisplayName("비인증 사용자는 직원용 도착 확인 API에 접근할 수 없다")
    void anonymous_cannotCheckIn() throws Exception {
        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/check-in",
                        10L
                ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("보호자는 직원용 도착 확인 API에 접근할 수 없다")
    void guardian_cannotCheckIn() throws Exception {
        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/check-in",
                        10L
                ).with(authentication(guardianAuthentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비인증 사용자는 병원 확정 예약 취소 API에 접근할 수 없다")
    void anonymous_cannotCancelConfirmedReservation() throws Exception {
        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/cancel",
                        10L
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"병원 사정\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("보호자는 병원 확정 예약 취소 API에 접근할 수 없다")
    void guardian_cannotCancelConfirmedReservation() throws Exception {
        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/cancel",
                        10L
                )
                        .with(authentication(guardianAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"병원 사정\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("병원 스태프는 병원 확정 예약 취소 API를 호출할 수 있다")
    void hospitalStaff_canCancelConfirmedReservation() throws Exception {
        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/cancel",
                        10L
                ).with(authentication(memberAuthentication(
                        50L,
                        "HOSPITAL_STAFF"
                )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"응급수술로 진료 불가\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(hospitalReservationApplicationService).cancelConfirmedByHospital(
                50L,
                10L,
                "응급수술로 진료 불가"
        );
    }

    @Test
    @DisplayName("병원 취소 사유가 빈 문자열이면 400으로 거부한다")
    void cancel_blankReason_returnsBadRequest() throws Exception {
        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/cancel",
                        10L
                ).with(authentication(memberAuthentication(50L, "HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("병원 취소 사유가 255자를 초과하면 400으로 거부한다")
    void cancel_reasonTooLong_returnsBadRequest() throws Exception {
        String reason = "가".repeat(256);

        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/cancel",
                        10L
                ).with(authentication(memberAuthentication(50L, "HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UnsupportedCancelReason(reason)
                        )))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("병원 스태프는 직원용 도착 확인 API를 호출할 수 있다")
    void hospitalStaff_canCheckIn() throws Exception {
        given(hospitalReservationApplicationService.checkIn(50L, 10L))
                .willReturn(new ReservationCheckInResponse(
                        10L,
                        ReservationStatus.CHECKED_IN,
                        LocalDateTime.of(2026, 8, 6, 10, 0)
                                .atZone(SEOUL_ZONE_ID)
                                .toOffsetDateTime()
                ));

        mockMvc.perform(patch(
                        "/api/hospital/reservations/{reservationId}/check-in",
                        10L
                ).with(authentication(memberAuthentication(
                        50L,
                        "HOSPITAL_STAFF"
                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reservationId").value(10L))
                .andExpect(jsonPath("$.data.status").value("CHECKED_IN"));

        verify(hospitalReservationApplicationService).checkIn(50L, 10L);
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

    @Test
    @DisplayName("보호자는 노쇼 수동 확정 API에 접근할 수 없다")
    void guardian_cannotConfirmNoShow() throws Exception {
        mockMvc.perform(patch(
                                "/api/hospital/reservations/{reservationId}/no-show",
                                10L
                        )
                        .with(authentication(guardianAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"미방문 확인\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("보호자는 노쇼 정정 API에 접근할 수 없다")
    void guardian_cannotRestoreNoShow() throws Exception {
        mockMvc.perform(patch(
                                "/api/hospital/reservations/{reservationId}/restore",
                                10L
                        )
                        .with(authentication(guardianAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"현장 도착 확인\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("병원 스태프는 사유를 포함해 노쇼를 수동 확정할 수 있다")
    void hospitalStaff_canConfirmNoShow() throws Exception {
        mockMvc.perform(patch(
                                "/api/hospital/reservations/{reservationId}/no-show",
                                10L
                        )
                        .with(authentication(memberAuthentication(
                                50L,
                                "HOSPITAL_STAFF"
                        )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"미방문 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(hospitalReservationApplicationService).confirmNoShow(
                50L,
                10L,
                "미방문 확인"
        );
    }

    @Test
    @DisplayName("노쇼 확정 사유가 공백이면 400으로 거부한다")
    void confirmNoShow_withBlankReason_returnsBadRequest() throws Exception {
        mockMvc.perform(patch(
                                "/api/hospital/reservations/{reservationId}/no-show",
                                10L
                        )
                        .with(authentication(memberAuthentication(
                                50L,
                                "HOSPITAL_STAFF"
                        )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("노쇼 정정 사유가 없으면 400으로 거부한다")
    void restoreNoShow_withoutReason_returnsBadRequest() throws Exception {
        mockMvc.perform(patch(
                                "/api/hospital/reservations/{reservationId}/restore",
                                10L
                        )
                        .with(authentication(memberAuthentication(
                                50L,
                                "HOSPITAL_STAFF"
                        )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
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

    private record UnsupportedCancelReason(String reason) {
    }
}
