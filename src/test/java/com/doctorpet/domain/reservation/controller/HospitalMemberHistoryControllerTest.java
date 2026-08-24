package com.doctorpet.domain.reservation.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.reservation.dto.response.HospitalMemberHistoryItemResponse;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.service.HospitalMemberHistoryQueryService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HospitalMemberHistoryController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class HospitalMemberHistoryControllerTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long RESERVATION_ID = 500L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalMemberHistoryQueryService hospitalMemberHistoryQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort;

    @Test
    @DisplayName("병원 스태프는 예약으로 회원 이력을 페이지로 받고, 인증 회원 id·예약 id·페이지가 서비스로 전달된다")
    void hospitalStaff_getsHistory() throws Exception {
        HospitalMemberHistoryItemResponse withPayment = new HospitalMemberHistoryItemResponse(
                500L, LocalDateTime.of(2026, 8, 20, 9, 0), "초코", "DOG",
                ReservationStatus.TREATMENT_COMPLETED, 900L, PaymentStatus.PAID, 30000
        );
        HospitalMemberHistoryItemResponse noPayment = new HospitalMemberHistoryItemResponse(
                499L, LocalDateTime.of(2026, 8, 1, 9, 0), "초코", "DOG",
                ReservationStatus.CANCELED, null, null, null
        );
        given(hospitalMemberHistoryQueryService.getMemberHistory(
                eq(MEMBER_ID), eq(RESERVATION_ID), eq(PageRequest.of(0, 20))))
                .willReturn(new PageImpl<>(List.of(withPayment, noPayment), PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/hospital/reservations/{id}/member-history", RESERVATION_ID)
                        .with(authentication(hospitalStaffAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].reservationId").value(500))
                .andExpect(jsonPath("$.data.content[0].reservationStatus").value("TREATMENT_COMPLETED"))
                .andExpect(jsonPath("$.data.content[0].paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.data.content[0].amount").value(30000))
                .andExpect(jsonPath("$.data.content[1].paymentStatus").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        verify(hospitalMemberHistoryQueryService)
                .getMemberHistory(MEMBER_ID, RESERVATION_ID, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("보호자는 병원 회원 이력에 접근할 수 없다(403), 서비스도 호출되지 않는다")
    void guardian_isForbidden() throws Exception {
        mockMvc.perform(get("/api/hospital/reservations/{id}/member-history", RESERVATION_ID)
                        .with(authentication(guardianAuthentication())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(hospitalMemberHistoryQueryService);
    }

    private UsernamePasswordAuthenticationToken hospitalStaffAuthentication() {
        return createAuthentication("HOSPITAL_STAFF");
    }

    private UsernamePasswordAuthenticationToken guardianAuthentication() {
        return createAuthentication("GUARDIAN");
    }

    private UsernamePasswordAuthenticationToken createAuthentication(String role) {
        MemberPrincipal principal = new MemberPrincipal(MEMBER_ID, "staff@example.com", role);
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
