package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentOfflineSettlementService;
import com.doctorpet.global.config.SecurityConfig;
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
 * Level 2 — 오프라인 정산 계약·인가 검증(#36). 필터 체인을 켠 채 /api/hospital/** 매처(HOSPITAL_STAFF)와
 * 컨트롤러 응답(200·409)을 확인한다.
 */
@WebMvcTest(controllers = HospitalPaymentSettlementController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalPaymentSettlementControllerTest {

    private static final String URL = "/api/hospital/payments/1/offline-settle";
    private static final Long STAFF_MEMBER_ID = 9L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentOfflineSettlementService paymentOfflineSettlementService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    private PaymentHistoryResponse settled() {
        return new PaymentHistoryResponse(1L, 100L, PaymentStatus.OFFLINE_PAID, PaymentChannel.OFFLINE,
                50000, "VISA", "1234",
                LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("병원 스태프는 오프라인 정산 시 200과 OFFLINE_PAID 결과를 받고, 인증된 스태프 id로 서비스를 호출한다")
    void settle_success() throws Exception {
        given(paymentOfflineSettlementService.settle(1L, STAFF_MEMBER_ID)).willReturn(settled());

        mockMvc.perform(patch(URL).with(authentication(staff(STAFF_MEMBER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("OFFLINE_PAID"))
                .andExpect(jsonPath("$.data.paymentChannel").value("OFFLINE"));

        verify(paymentOfflineSettlementService).settle(1L, STAFF_MEMBER_ID);
    }

    @Test
    @DisplayName("허용되지 않은 상태면 409(PAYMENT_006)")
    void settle_preconditionFailed() throws Exception {
        given(paymentOfflineSettlementService.settle(anyLong(), anyLong()))
                .willThrow(new ServiceException(PaymentErrorCode.OFFLINE_PRECONDITION_FAILED));

        mockMvc.perform(patch(URL).with(authentication(staff(STAFF_MEMBER_ID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_006"));
    }

    @Test
    @DisplayName("보호자 role은 오프라인 정산 접근이 거부된다(403)")
    void guardian_isForbidden() throws Exception {
        mockMvc.perform(patch(URL).with(authentication(guardian())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비인증 요청은 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(patch(URL))
                .andExpect(status().isUnauthorized());
    }

    private Authentication staff(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }

    private Authentication guardian() {
        MemberPrincipal principal = new MemberPrincipal(5L, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
