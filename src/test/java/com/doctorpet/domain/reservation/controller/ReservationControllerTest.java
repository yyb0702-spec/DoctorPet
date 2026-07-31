package com.doctorpet.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.response.ReservationDetailResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationListItemResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationPageResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationProgressStatus;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.service.ReservationApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
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
    private ReservationApplicationService reservationApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter 생성자 의존성(탈퇴 회원 Access Token 블랙리스트 체크, 리뷰 지적 P1
    // 대응) — 없으면 컨텍스트 로딩이 실패한다.
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @Test
    @DisplayName("보호자는 예약을 요청할 수 있다")
    void request_guardian_returnsCreated() throws Exception {
        ReservationRequest request = validRequest();
        given(reservationApplicationService.request(
                eq(1L),
                any(ReservationRequest.class)
        ))
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
                5L
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
                5L
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

        verify(reservationApplicationService).cancel(1L, 10L);
    }

    @Test
    @DisplayName("병원 스태프는 보호자 예약을 취소할 수 없다")
    void cancel_hospitalStaff_returnsForbidden() throws Exception {
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", 10L)
                        .with(authentication(memberAuthentication(1L, "HOSPITAL_STAFF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("보호자는 본인 예약 목록을 페이징해 조회할 수 있다")
    void getMyReservations_guardian_returnsPage() throws Exception {
        ReservationListItemResponse item = new ReservationListItemResponse(
                10L,
                3L,
                "닥터펫 동물병원",
                2L,
                "초코",
                LocalDateTime.of(2026, 7, 25, 14, 0),
                ReservationStatus.CONFIRMED,
                null,
                ReservationProgressStatus.RESERVATION_CONFIRMED
        );
        given(reservationApplicationService.getMyReservations(
                eq(1L),
                any(ReservationListCondition.class)
        )).willReturn(new ReservationPageResponse(
                List.of(item),
                0,
                20,
                1,
                1,
                true,
                true
        ));

        mockMvc.perform(get("/api/reservations")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .param("status", "CONFIRMED")
                        .param("from", "2026-07-01")
                        .param("to", "2026-07-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].reservationId").value(10L))
                .andExpect(jsonPath("$.data.content[0].hospitalName")
                        .value("닥터펫 동물병원"))
                .andExpect(jsonPath("$.data.content[0].progressStatus")
                        .value("RESERVATION_CONFIRMED"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("보호자는 본인 예약 상세를 조회할 수 있다")
    void getMyReservation_guardian_returnsDetail() throws Exception {
        given(reservationApplicationService.getMyReservation(1L, 10L))
                .willReturn(new ReservationDetailResponse(
                        10L,
                        ReservationStatus.CONFIRMED,
                        null,
                        ReservationProgressStatus.RESERVATION_CONFIRMED,
                        new ReservationDetailResponse.HospitalResponse(
                                3L,
                                "닥터펫 동물병원",
                                "서울특별시 중구",
                                "02-1234-5678"
                        ),
                        new ReservationDetailResponse.PetSnapshotResponse(
                                2L,
                                "초코",
                                "DOG"
                        ),
                        new ReservationDetailResponse.SlotResponse(
                                4L,
                                LocalDateTime.of(2026, 7, 25, 14, 0),
                                LocalDateTime.of(2026, 7, 25, 14, 30)
                        ),
                        null,
                        LocalDateTime.of(2026, 7, 23, 10, 0),
                        null
                ));

        mockMvc.perform(get("/api/reservations/{reservationId}", 10L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId").value(10L))
                .andExpect(jsonPath("$.data.petSnapshot.name").value("초코"))
                .andExpect(jsonPath("$.data.slot.slotId").value(4L));
    }

    @Test
    @DisplayName("병원 스태프는 보호자 예약 목록을 조회할 수 없다")
    void getMyReservations_hospitalStaff_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/reservations")
                        .with(authentication(memberAuthentication(
                                1L,
                                "HOSPITAL_STAFF"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("미인증 사용자는 보호자 예약 목록을 조회할 수 없다")
    void getMyReservations_unauthenticated_returnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/api/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("잘못된 날짜 형식은 400으로 처리한다")
    void getMyReservations_invalidDate_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/reservations")
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .param("from", "2026-99-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("0 이하의 예약 ID는 400으로 처리한다")
    void getMyReservation_nonPositiveId_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/reservations/{reservationId}", 0L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("페이지 크기가 100을 초과하면 400을 반환한다")
    void getMyReservations_sizeOverMax_returnBadRequest()
        throws Exception{

        mockMvc.perform(get("/api/reservations")
                        .with(authentication(
                                memberAuthentication(1L, "GUARDIAN")
                        ))
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private ReservationRequest validRequest() {
        return new ReservationRequest(2L, 4L, 5L);
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
