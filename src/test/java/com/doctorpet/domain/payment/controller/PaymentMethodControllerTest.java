package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
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
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter 생성자 의존성(탈퇴 회원 Access Token 블랙리스트 체크, 리뷰 지적 P1
    // 대응) — 없으면 컨텍스트 로딩이 실패한다.
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("결제수단 등록 성공 시 201과 표시용 카드 정보를 반환하고, 인증된 회원 id로 서비스를 호출한다")
    void register_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("valid_billing_key");
        given(paymentMethodService.register(eq(MEMBER_ID), any(PaymentMethodRegisterRequest.class)))
                .willReturn(new PaymentMethodResponse(100L, "SHINHAN", "1234", "ACTIVE", LocalDateTime.now()));

        mockMvc.perform(post("/api/payment-methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.cardBrand").value("SHINHAN"))
                .andExpect(jsonPath("$.data.cardLast4").value("1234"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(paymentMethodService).register(eq(MEMBER_ID), any(PaymentMethodRegisterRequest.class));
    }

    @Test
    @DisplayName("빌링키가 비어 있으면 400과 COMMON_001(VALIDATION_FAILED)을 반환한다")
    void register_blankBillingKey() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("");

        mockMvc.perform(post("/api/payment-methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("무효한 빌링키면 400과 PAYMENT_METHOD_001을 반환한다")
    void register_invalidBillingKey() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("forged_key");
        given(paymentMethodService.register(eq(MEMBER_ID), any(PaymentMethodRegisterRequest.class)))
                .willThrow(new ServiceException(PaymentMethodErrorCode.INVALID_BILLING_KEY));

        mockMvc.perform(post("/api/payment-methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_001"));
    }

    @Test
    @DisplayName("본인 결제수단 조회 시 200과 목록을 반환하고, 인증된 회원 id로 조회한다")
    void getMyPaymentMethods_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        given(paymentMethodService.getMyPaymentMethods(MEMBER_ID)).willReturn(List.of(
                new PaymentMethodResponse(100L, "SHINHAN", "1234", "ACTIVE", LocalDateTime.now())));

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
    @DisplayName("빌링키가 허용 바이트를 초과하면(ASCII 513B) 400과 COMMON_001을 반환하고 서비스를 호출하지 않는다")
    void register_billingKeyTooLong() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        // 513바이트 = 512바이트 상한 초과.
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("a".repeat(513));

        mockMvc.perform(post("/api/payment-methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        org.mockito.Mockito.verifyNoInteractions(paymentMethodService);
    }

    @Test
    @DisplayName("문자 수는 적어도 UTF-8 바이트가 상한을 넘으면(한글 200자=600B) 400을 반환한다")
    void register_billingKeyMultibyteExceedsByteLimit() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(memberAuthentication(MEMBER_ID));
        // 한글 1자 = UTF-8 3바이트. 200자 = 600바이트로 512바이트 상한 초과(문자 수(200)만 보면 통과했을 입력).
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("가".repeat(200));

        mockMvc.perform(post("/api/payment-methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        org.mockito.Mockito.verifyNoInteractions(paymentMethodService);
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

    private Authentication memberAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
