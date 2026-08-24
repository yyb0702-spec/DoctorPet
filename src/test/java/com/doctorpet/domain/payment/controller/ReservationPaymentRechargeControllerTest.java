package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
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
 * Level 2 — 보호자 셀프 복구(다시 결제) 컨트롤러 계약(상태코드·ApiResponse 포맷·Validation·인증 주체) 검증(고도화 3.3).
 * 필터는 addFilters=false로 끄고 인증 주체를 SecurityContext에 직접 주입한다(인가 URL 강제는
 * ReservationPaymentRechargeAuthorizationTest가 담당). 회원 식별이 요청 body/path가 아니라 인증 주체(memberId)로
 * 서비스에 전달되는지 확인한다.
 */
@WebMvcTest(controllers = ReservationPaymentRechargeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class ReservationPaymentRechargeControllerTest {

    private static final Long GUARDIAN_ID = 5L;
    private static final Long PAYMENT_METHOD_ID = 7L;
    private static final String RECHARGE_URL = "/api/reservations/100/payments/recharge";
    private static final String RECHARGE_BODY = "{\"paymentMethodId\":" + PAYMENT_METHOD_ID + "}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentApplicationService paymentApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("셀프 복구 성공 시 201과 새 결제 결과를 반환하고, 인증된 보호자 id·지정 결제수단으로 recharge를 호출한다")
    void recharge_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication(GUARDIAN_ID));
        given(paymentApplicationService.recharge(eq(100L), eq(GUARDIAN_ID), eq(PAYMENT_METHOD_ID)))
                .willReturn(new PaymentChargeResponse(2L, 100L, PaymentStatus.PAID, 50000, "VISA", "1234", null));

        mockMvc.perform(post(RECHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECHARGE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value(2))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.amount").value(50000));

        verify(paymentApplicationService).recharge(100L, GUARDIAN_ID, PAYMENT_METHOD_ID);
    }

    @Test
    @DisplayName("복구 전제(활성 결제가 OFFLINE_REQUIRED 아님) 위반은 서비스에서 RECHARGE_PRECONDITION_FAILED(PAYMENT_018, 409)로 응답한다")
    void recharge_preconditionFailed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication(GUARDIAN_ID));
        given(paymentApplicationService.recharge(eq(100L), eq(GUARDIAN_ID), eq(PAYMENT_METHOD_ID)))
                .willThrow(new ServiceException(PaymentErrorCode.RECHARGE_PRECONDITION_FAILED));

        mockMvc.perform(post(RECHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECHARGE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_018"));
    }

    @Test
    @DisplayName("경합에서 진 요청(이미 대체됨)은 PAYMENT_ALREADY_SUPERSEDED(PAYMENT_017, 409)로 응답한다")
    void recharge_alreadySuperseded() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication(GUARDIAN_ID));
        given(paymentApplicationService.recharge(eq(100L), eq(GUARDIAN_ID), eq(PAYMENT_METHOD_ID)))
                .willThrow(new ServiceException(PaymentErrorCode.PAYMENT_ALREADY_SUPERSEDED));

        mockMvc.perform(post(RECHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECHARGE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_017"));
    }

    @Test
    @DisplayName("결제수단 id가 없으면 400(Validation) — 셀프 복구는 결제수단을 반드시 지정해야 한다")
    void recharge_missingPaymentMethodId_badRequest() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication(GUARDIAN_ID));

        mockMvc.perform(post(RECHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private Authentication guardianAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
