package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Level 2 — 보호자 결제 내역 조회 계약·인가 검증(#47). 필터 체인을 켠 채 role 매처(GUARDIAN)와
 * 컨트롤러 응답(표시용 카드정보 노출·민감정보 미노출)을 함께 확인한다.
 */
@WebMvcTest(controllers = ReservationPaymentQueryController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class ReservationPaymentQueryControllerTest {

    private static final String URL = "/api/reservations/100/payments";
    private static final Long GUARDIAN_ID = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentQueryService paymentQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    private PaymentHistoryResponse paid() {
        return new PaymentHistoryResponse(1L, 100L, PaymentStatus.PAID, PaymentChannel.BILLING_KEY,
                50000, "VISA", "1234", LocalDateTime.now(), LocalDateTime.now(), null, null);
    }

    @Test
    @DisplayName("보호자는 본인 예약 결제 내역을 200으로 받고, 표시용 카드정보만 노출된다")
    void guardian_success() throws Exception {
        given(paymentQueryService.getForGuardian(100L, GUARDIAN_ID)).willReturn(List.of(paid()));

        mockMvc.perform(get(URL).with(authentication(guardian(GUARDIAN_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].status").value("PAID"))
                .andExpect(jsonPath("$.data[0].cardLast4Snapshot").value("1234"))
                // 내부 식별자(pgPaymentId·merchantPaymentId)와 내부 처리 코드(failureReason)는 응답에 없어야 한다.
                .andExpect(jsonPath("$.data[0].pgPaymentId").doesNotExist())
                .andExpect(jsonPath("$.data[0].merchantPaymentId").doesNotExist())
                .andExpect(jsonPath("$.data[0].failureReason").doesNotExist());

        verify(paymentQueryService).getForGuardian(100L, GUARDIAN_ID);
    }

    @Test
    @DisplayName("결제 전이면 200과 빈 배열을 반환한다")
    void guardian_empty() throws Exception {
        given(paymentQueryService.getForGuardian(100L, GUARDIAN_ID)).willReturn(List.of());

        mockMvc.perform(get(URL).with(authentication(guardian(GUARDIAN_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("본인 예약이 아니면 403(COMMON_003)")
    void guardian_notOwner_forbidden() throws Exception {
        given(paymentQueryService.getForGuardian(eq(100L), anyLong()))
                .willThrow(new ServiceException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get(URL).with(authentication(guardian(GUARDIAN_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("병원 스태프 role은 보호자 결제내역 조회가 거부된다(403)")
    void staff_isForbidden() throws Exception {
        mockMvc.perform(get(URL).with(authentication(staff())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비인증 요청은 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    private Authentication guardian(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }

    private Authentication staff() {
        MemberPrincipal principal = new MemberPrincipal(9L, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }
}
