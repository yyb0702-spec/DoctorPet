package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Level 2 — 진료비 청구 컨트롤러 계약(상태코드·ApiResponse 포맷·Validation·인증 주체) 검증.
 * 필터는 addFilters=false로 끄고 인증 주체를 SecurityContext에 직접 주입한다(인가 URL 강제는 인가 테스트가 담당).
 * 스태프 식별이 요청 body/path가 아니라 인증 주체(memberId)로 서비스에 전달되는지 확인한다.
 */
@WebMvcTest(controllers = HospitalPaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalPaymentControllerTest {

    private static final Long STAFF_MEMBER_ID = 9L;
    private static final String CHARGE_URL = "/api/hospital/reservations/100/payments";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentApplicationService paymentApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("청구 성공 시 201과 결제 결과를 반환하고, 인증된 스태프 id로 서비스를 호출한다")
    void charge_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(50000)))
                .willReturn(new PaymentChargeResponse(1L, 100L, PaymentStatus.PAID, 50000, "VISA", "1234", null));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value(1))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.cardLast4Snapshot").value("1234"));

        verify(paymentApplicationService).charge(100L, STAFF_MEMBER_ID, 50000);
    }

    @Test
    @DisplayName("승인 실패는 201과 status=OFFLINE_REQUIRED로 응답한다(게이트웨이 예외가 500으로 새지 않는다)")
    void charge_offlineRequired() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(50000)))
                .willReturn(new PaymentChargeResponse(1L, 100L, PaymentStatus.OFFLINE_REQUIRED, 50000, "VISA", "1234", "NON_RETRIABLE"));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OFFLINE_REQUIRED"))
                .andExpect(jsonPath("$.data.failureReason").value("NON_RETRIABLE"));
    }

    @Test
    @DisplayName("금액이 0 이하이면 400과 COMMON_001(VALIDATION_FAILED)을 반환하고 서비스를 호출하지 않는다")
    void charge_nonPositiveAmount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(paymentApplicationService);
    }

    @Test
    @DisplayName("금액이 없으면 400과 COMMON_001을 반환한다")
    void charge_missingAmount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(paymentApplicationService);
    }

    @Test
    @DisplayName("상한 초과 금액은 서비스에서 INVALID_AMOUNT(PAYMENT_001, 400)로 응답한다")
    void charge_overMaxAmount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(3_000_001)))
                .willThrow(new ServiceException(PaymentErrorCode.INVALID_AMOUNT));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":3000001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_001"));
    }

    private Authentication staffAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }
}
