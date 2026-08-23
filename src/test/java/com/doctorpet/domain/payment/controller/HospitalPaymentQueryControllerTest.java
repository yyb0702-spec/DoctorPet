package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.HospitalPaymentListItemResponse;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.service.HospitalPaymentQueryService;
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

@WebMvcTest(controllers = HospitalPaymentQueryController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class HospitalPaymentQueryControllerTest {

    private static final Long MEMBER_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HospitalPaymentQueryService hospitalPaymentQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort;

    @Test
    @DisplayName("병원 스태프는 자병원 결제 목록을 페이지로 받고, 인증 회원 id와 페이지 요청이 서비스로 전달된다")
    void hospitalStaff_getsPayments() throws Exception {
        HospitalPaymentListItemResponse item = new HospitalPaymentListItemResponse(
                100L, 31L, 7L, "초코",
                LocalDateTime.of(2026, 8, 20, 9, 0),
                200L, PaymentStatus.OFFLINE_REQUIRED, 30000,
                null, null, null,
                LocalDateTime.of(2026, 8, 20, 9, 30)
        );
        given(hospitalPaymentQueryService.getHospitalPayments(eq(MEMBER_ID), eq(PageRequest.of(0, 20))))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/hospital/payments")
                        .with(authentication(hospitalStaffAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].reservationId").value(100))
                .andExpect(jsonPath("$.data.content[0].paymentStatus").value("OFFLINE_REQUIRED"))
                .andExpect(jsonPath("$.data.content[0].amount").value(30000))
                .andExpect(jsonPath("$.data.content[0].failedAt").value("2026-08-20T09:30:00"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.first").value(true));

        verify(hospitalPaymentQueryService).getHospitalPayments(MEMBER_ID, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("page·size 파라미터가 서비스의 PageRequest로 그대로 전달된다")
    void passesPageParams() throws Exception {
        given(hospitalPaymentQueryService.getHospitalPayments(eq(MEMBER_ID), eq(PageRequest.of(2, 5))))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        mockMvc.perform(get("/api/hospital/payments")
                        .param("page", "2").param("size", "5")
                        .with(authentication(hospitalStaffAuthentication())))
                .andExpect(status().isOk());

        verify(hospitalPaymentQueryService).getHospitalPayments(MEMBER_ID, PageRequest.of(2, 5));
    }

    @Test
    @DisplayName("보호자는 병원 결제 목록에 접근할 수 없다(403), 서비스도 호출되지 않는다")
    void guardian_isForbidden() throws Exception {
        mockMvc.perform(get("/api/hospital/payments")
                        .with(authentication(guardianAuthentication())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(hospitalPaymentQueryService);
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
