package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.service.PaymentApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.MemberPrincipal;
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
 * Level 2 — 진료비 청구 인가(병원 스태프 전용) 검증. SecurityConfig의 hasRole("HOSPITAL_STAFF") 매처가
 * 실제로 동작하는지 필터 체인을 켠 채 확인한다. 자병원 일치 검증(서비스 계층)은 서비스 테스트가 담당한다.
 */
@WebMvcTest(controllers = HospitalPaymentController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalPaymentAuthorizationTest {

    private static final String CHARGE_URL = "/api/hospital/reservations/100/payments";
    private static final String BODY = "{\"draftToken\":\"0123456789abcdef0123456789abcdef\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentApplicationService paymentApplicationService;

    // 컨트롤러가 조회 서비스(#47)에도 의존하므로 컨텍스트 로딩용으로 목을 채운다.
    @MockitoBean
    private com.doctorpet.domain.payment.service.PaymentQueryService paymentQueryService;

    // JwtAuthenticationFilter 빈이 JwtTokenProvider·MemberBlacklistPort를 요구하므로 컨텍스트 로딩용으로 목을 채운다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @Test
    @DisplayName("병원 스태프(ROLE_HOSPITAL_STAFF)는 진료비 청구에 접근할 수 있다(201)")
    void hospitalStaff_isAllowed() throws Exception {
        given(paymentApplicationService.charge(anyLong(), anyLong(), anyString()))
                .willReturn(new PaymentChargeResponse(1L, 100L, PaymentStatus.PAID, 50000, "VISA", "1234", null));

        mockMvc.perform(post(CHARGE_URL)
                        .with(authentication(staffAuthentication(9L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("보호자(ROLE_GUARDIAN)는 진료비 청구 접근이 거부된다(403)")
    void guardian_isForbidden() throws Exception {
        mockMvc.perform(post(CHARGE_URL)
                        .with(authentication(guardianAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비인증 요청은 진료비 청구 접근이 거부된다(401)")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    private Authentication staffAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }

    private Authentication guardianAuthentication() {
        MemberPrincipal principal = new MemberPrincipal(5L, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }
}
