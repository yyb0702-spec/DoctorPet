package com.doctorpet.domain.payment.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.service.BillingKeyIssueApplicationService;
import com.doctorpet.domain.payment.dto.response.PaymentMethodResponse;
import com.doctorpet.domain.payment.exception.PaymentMethodErrorCode;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Level 2 — 결제수단 API 계약(상태코드·ApiResponse 포맷·Validation·인증 주체) 검증.
 * AuthControllerTest와 동일하게 Security 필터는 addFilters=false로 끄고, 인증 주체는
 * SecurityContext에 직접 주입한다. 보호자 권한(ROLE_GUARDIAN) URL 강제는 SecurityConfig 매처의
 * 책임으로, 필터를 켜는 통합 레벨에서 확인한다(여기서는 컨트롤러 계약에 집중).
 */
@WebMvcTest(controllers = PaymentMethodController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class PaymentMethodControllerTest {

    private static final Long MEMBER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PaymentMethodService paymentMethodService;

    @MockitoBean
    private BillingKeyIssueApplicationService billingKeyIssueApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter 생성자 의존성(탈퇴 회원 Access Token 블랙리스트 체크, 리뷰 지적 P1
    // 대응) — 없으면 컨텍스트 로딩이 실패한다.
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("빌링키 발급 시도 생성은 서버 발급 issueId와 콜백 주소만 반환한다")
    void issueBillingKey_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        given(billingKeyIssueApplicationService.issue(MEMBER_ID)).willReturn("server-issued-id");

        mockMvc.perform(post("/api/payment-methods/billing-key-issues"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.issueId").value("server-issued-id"))
                .andExpect(jsonPath("$.data.redirectUrl")
                        .value("http://localhost/api/payment-methods/billing-key-issues/server-issued-id/callback"));

        verify(billingKeyIssueApplicationService).issue(MEMBER_ID);
    }

    @Test
    @DisplayName("iframe 빌링키 완료는 인증 회원과 서버 발급 issueId를 함께 서비스에 전달한다")
    void completeBillingKeyIssue_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("valid_billing_key");
        given(billingKeyIssueApplicationService.complete(MEMBER_ID, "server-issued-id", "valid_billing_key"))
                .willReturn(new PaymentMethodResponse(100L, "SHINHAN", "1234", "ACTIVE", true, LocalDateTime.now()));

        mockMvc.perform(post("/api/payment-methods/billing-key-issues/{issueId}/complete", "server-issued-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(100));

        verify(billingKeyIssueApplicationService)
                .complete(MEMBER_ID, "server-issued-id", "valid_billing_key");
    }

    @Test
    @DisplayName("iframe 완료의 빈 빌링키는 서비스 호출 전 400으로 거부한다")
    void completeBillingKeyIssue_blankBillingKey() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("");

        mockMvc.perform(post("/api/payment-methods/billing-key-issues/{issueId}/complete", "server-issued-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("본인 결제수단 조회 시 200과 목록을 반환하고, 인증된 회원 id로 조회한다")
    void getMyPaymentMethods_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        given(paymentMethodService.getMyPaymentMethods(MEMBER_ID)).willReturn(List.of(
                new PaymentMethodResponse(100L, "SHINHAN", "1234", "ACTIVE", true, LocalDateTime.now())));

        mockMvc.perform(get("/api/payment-methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].cardLast4").value("1234"));

        verify(paymentMethodService).getMyPaymentMethods(MEMBER_ID);
    }

    @Test
    @DisplayName("결제수단 삭제 성공 시 204 No Content를 반환하고, 인증된 회원 id로 삭제한다")
    void delete_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));

        mockMvc.perform(delete("/api/payment-methods/{id}", 100L))
                .andExpect(status().isNoContent());

        verify(paymentMethodService).delete(MEMBER_ID, 100L);
    }

    @Test
    @DisplayName("타인 소유·미존재 결제수단 삭제는 404와 PAYMENT_METHOD_003을 반환한다")
    void delete_notFound() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        org.mockito.BDDMockito.willThrow(new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND))
                .given(paymentMethodService).delete(MEMBER_ID, 999L);

        mockMvc.perform(delete("/api/payment-methods/{id}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_003"));
    }

    @Test
    @DisplayName("기본 결제수단 지정은 인증된 회원 ID만 사용하고 기본값 정보를 반환한다")
    void setDefault_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        given(paymentMethodService.setDefault(MEMBER_ID, 100L))
                .willReturn(new PaymentMethodResponse(
                        100L, "SHINHAN", "1234", "ACTIVE", true, LocalDateTime.now()));

        mockMvc.perform(patch("/api/payment-methods/{paymentMethodId}/default", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.isDefault").value(true));

        verify(paymentMethodService).setDefault(MEMBER_ID, 100L);
    }

    private Authentication memberAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
