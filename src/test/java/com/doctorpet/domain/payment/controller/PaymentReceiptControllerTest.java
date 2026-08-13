package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentItemResponse;
import com.doctorpet.domain.payment.dto.response.PaymentReceiptResponse;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.RefundStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentReceiptService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Level 2 — 보호자 JSON 영수증 컨트롤러 계약(응답 포맷·인증 주체·에러 매핑) 검증(SA §9-4 영수증).
 * 필터는 addFilters=false로 끄고 인증 주체를 SecurityContext에 직접 주입한다(인가 URL 강제는 인가 테스트 담당).
 */
@WebMvcTest(controllers = PaymentReceiptController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class PaymentReceiptControllerTest {

    private static final Long GUARDIAN_MEMBER_ID = 5L;
    private static final String RECEIPT_URL = "/api/payments/1/receipt";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PaymentReceiptService paymentReceiptService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private MemberBlacklistPort memberBlacklistPort;
    @MockitoBean private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("영수증 조회는 200과 항목·총액·카드 스냅샷을 반환하고, 인증된 회원 id로 서비스를 호출한다")
    void getReceipt_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication());
        given(paymentReceiptService.getForGuardian(eq(1L), eq(GUARDIAN_MEMBER_ID))).willReturn(
                new PaymentReceiptResponse(
                        1L, 100L, 7L, GUARDIAN_MEMBER_ID, 11L, "나비", "CAT",
                        PaymentStatus.PAID, PaymentChannel.BILLING_KEY,
                        LocalDateTime.now(), null, "VISA", "1234",
                        List.of(new PaymentItemResponse("진찰료", 1, 20000, 20000),
                                new PaymentItemResponse("재진 할인", 1, -5000, -5000)),
                        15000, null, null));

        mockMvc.perform(get(RECEIPT_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value(1))
                .andExpect(jsonPath("$.data.reservationId").value(100))
                .andExpect(jsonPath("$.data.petName").value("나비"))
                .andExpect(jsonPath("$.data.totalAmount").value(15000))
                .andExpect(jsonPath("$.data.cardLast4Snapshot").value("1234"))
                .andExpect(jsonPath("$.data.items[1].amount").value(-5000))
                // 내부 식별자·처리 분류 코드·환불 사유는 응답에 없다(계약 회귀 방지).
                .andExpect(jsonPath("$.data.merchantPaymentId").doesNotExist())
                .andExpect(jsonPath("$.data.pgPaymentId").doesNotExist())
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.refundReason").doesNotExist());

        verify(paymentReceiptService).getForGuardian(1L, GUARDIAN_MEMBER_ID);
    }

    @Test
    @DisplayName("REFUNDED 영수증은 환불 상태·환불 일시를 함께 내려준다")
    void getReceipt_refunded() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication());
        LocalDateTime refundedAt = LocalDateTime.of(2026, 8, 11, 9, 30);
        given(paymentReceiptService.getForGuardian(eq(1L), eq(GUARDIAN_MEMBER_ID))).willReturn(
                new PaymentReceiptResponse(
                        1L, 100L, 7L, GUARDIAN_MEMBER_ID, 11L, "나비", "CAT",
                        PaymentStatus.REFUNDED, PaymentChannel.BILLING_KEY,
                        LocalDateTime.now(), null, "VISA", "1234",
                        List.of(), 50000, RefundStatus.COMPLETED, refundedAt));

        mockMvc.perform(get(RECEIPT_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.refundedAt").exists());
    }

    @Test
    @DisplayName("발급 대상이 아닌 상태는 409와 PAYMENT_013(RECEIPT_NOT_AVAILABLE)을 반환한다")
    void getReceipt_notAvailableStatus() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication());
        given(paymentReceiptService.getForGuardian(eq(1L), eq(GUARDIAN_MEMBER_ID)))
                .willThrow(new ServiceException(PaymentErrorCode.RECEIPT_NOT_AVAILABLE));

        mockMvc.perform(get(RECEIPT_URL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_013"));
    }

    @Test
    @DisplayName("타인의 결제를 조회하면 403을 반환한다")
    void getReceipt_forbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(guardianAuthentication());
        given(paymentReceiptService.getForGuardian(eq(1L), eq(GUARDIAN_MEMBER_ID)))
                .willThrow(new ServiceException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get(RECEIPT_URL))
                .andExpect(status().isForbidden());
    }

    private Authentication guardianAuthentication() {
        MemberPrincipal principal =
                new MemberPrincipal(GUARDIAN_MEMBER_ID, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
