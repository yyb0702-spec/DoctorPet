package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentRefundService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
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

/**
 * Level 2 — 진료비 환불 계약·인가 검증(#37). 필터 체인을 켠 채 /api/hospital/** 매처(HOSPITAL_STAFF)와
 * 컨트롤러 응답(200·409·502·400)을 확인한다. 스태프 식별이 요청 body/path가 아니라 인증 주체(memberId)로
 * 서비스에 전달되는지도 함께 고정한다(보안 — 요청 값 신뢰 금지).
 */
@WebMvcTest(controllers = HospitalPaymentRefundController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalPaymentRefundControllerTest {

    private static final String URL = "/api/hospital/payments/1/refund";
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final String BODY = "{\"reason\":\"진료비 오청구\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRefundService paymentRefundService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // JwtAuthenticationFilter 생성자 의존성

    private PaymentHistoryResponse refunded() {
        return new PaymentHistoryResponse(1L, 100L, PaymentStatus.REFUNDED, PaymentChannel.BILLING_KEY,
                50000, "VISA", "1234",
                LocalDateTime.now(), LocalDateTime.now(), null, null, LocalDateTime.now());
    }

    @Test
    @DisplayName("병원 스태프는 환불 시 200과 REFUNDED·환불 시각을 받고, 인증된 스태프 id로 서비스를 호출한다")
    void refund_success() throws Exception {
        given(paymentRefundService.refund(eq(1L), eq(STAFF_MEMBER_ID), eq("진료비 오청구")))
                .willReturn(refunded());

        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.refundedAt").exists())
                // 채널은 결제 시점 값을 유지한다 — 무엇으로 결제된 건을 되돌렸는지가 남아야 한다.
                .andExpect(jsonPath("$.data.paymentChannel").value("BILLING_KEY"))
                // 환불 사유·처리 스태프·PG 취소 식별자는 병원 내부 감사값이라 응답에 없어야 한다.
                .andExpect(jsonPath("$.data.reason").doesNotExist())
                .andExpect(jsonPath("$.data.refundedBy").doesNotExist())
                .andExpect(jsonPath("$.data.pgCancelId").doesNotExist());

        verify(paymentRefundService).refund(1L, STAFF_MEMBER_ID, "진료비 오청구");
    }

    @Test
    @DisplayName("환불 대상 상태가 아니면 409(PAYMENT_008)")
    void refund_preconditionFailed() throws Exception {
        given(paymentRefundService.refund(anyLong(), anyLong(), anyString()))
                .willThrow(new ServiceException(PaymentErrorCode.REFUND_PRECONDITION_FAILED));

        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_008"));
    }

    @Test
    @DisplayName("환불이 이미 진행 중이면 409(PAYMENT_009)")
    void refund_inProgress() throws Exception {
        given(paymentRefundService.refund(anyLong(), anyLong(), anyString()))
                .willThrow(new ServiceException(PaymentErrorCode.REFUND_IN_PROGRESS));

        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_009"));
    }

    @Test
    @DisplayName("PG 취소 실패는 502(PAYMENT_010)로 응답하고 게이트웨이 오류 원문을 노출하지 않는다")
    void refund_gatewayFailed() throws Exception {
        given(paymentRefundService.refund(anyLong(), anyLong(), anyString()))
                .willThrow(new ServiceException(PaymentErrorCode.REFUND_GATEWAY_FAILED));

        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_010"))
                // 스태프가 재시도 가능함을 알 수 있는 안내 문구만 나가고, 공급자 코드는 담기지 않는다.
                .andExpect(jsonPath("$.message").value("환불 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("사유가 없으면 400과 COMMON_001을 반환하고 서비스를 호출하지 않는다")
    void refund_missingReason() throws Exception {
        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        // 사유 없는 환불은 감사 추적이 불가능하므로 서비스에 도달해서는 안 된다.
        verifyNoInteractions(paymentRefundService);
    }

    @Test
    @DisplayName("사유가 공백뿐이면 400과 COMMON_001을 반환한다")
    void refund_blankReason() throws Exception {
        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(paymentRefundService);
    }

    @Test
    @DisplayName("사유가 200자를 넘으면 400과 COMMON_001을 반환한다")
    void refund_tooLongReason() throws Exception {
        String tooLong = "가".repeat(201);

        mockMvc.perform(post(URL).with(authentication(staff(STAFF_MEMBER_ID))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        // 컬럼 길이(200)를 넘는 값이 서비스까지 가면 DB 오류(500)가 된다 — 400에서 걸러야 한다.
        verifyNoInteractions(paymentRefundService);
    }

    @Test
    @DisplayName("보호자 role은 환불 접근이 거부된다(403)")
    void guardian_isForbidden() throws Exception {
        mockMvc.perform(post(URL).with(authentication(guardian())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(paymentRefundService);
    }

    @Test
    @DisplayName("비인증 요청은 401")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentRefundService);
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
