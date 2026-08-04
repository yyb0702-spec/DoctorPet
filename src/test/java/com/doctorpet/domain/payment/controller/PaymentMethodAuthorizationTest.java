package com.doctorpet.domain.payment.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
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
 * Level 2 — /api/payment-methods 인가(보호자 전용) 검증. SecurityConfig의 hasRole("GUARDIAN")
 * 매처가 실제로 동작하는지 보기 위해 필터 체인을 켠 채(addFilters 기본값 true) 실행한다.
 * PaymentMethodControllerTest(addFilters=false)가 다루지 못하는 인가 회귀를 이 클래스가 보완한다.
 */
@WebMvcTest(controllers = PaymentMethodController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class PaymentMethodAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentMethodService paymentMethodService;

    // 필터 체인이 켜져 있어도 Bearer 토큰이 없으면 JwtAuthenticationFilter는 인증을 세팅하지 않는다.
    // JwtAuthenticationFilter 빈이 JwtTokenProvider를 요구하므로 컨텍스트 로딩을 위해 목으로 채운다.
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    // JwtAuthenticationFilter 생성자 의존성(탈퇴 회원 Access Token 블랙리스트 체크, 리뷰 지적 P1
    // 대응) — 없으면 컨텍스트 로딩이 실패한다. 이 클래스의 테스트는 Authorization 헤더 없이
    // authentication()으로 SecurityContext를 직접 주입하므로 실제로 호출되지는 않는다.
    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @Test
    @DisplayName("보호자(ROLE_GUARDIAN)는 결제수단 조회에 접근할 수 있다(200)")
    void guardian_isAllowed() throws Exception {
        given(paymentMethodService.getMyPaymentMethods(1L)).willReturn(List.of());

        mockMvc.perform(get("/api/payment-methods").with(authentication(guardianAuthentication(1L))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("병원 스태프(ROLE_HOSPITAL_STAFF)는 결제수단 API 접근이 거부된다(403)")
    void hospitalStaff_isForbidden() throws Exception {
        mockMvc.perform(get("/api/payment-methods").with(authentication(hospitalStaffAuthentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비인증 요청은 결제수단 API 접근이 거부된다(401)")
    void anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/payment-methods"))
                .andExpect(status().isUnauthorized());
    }

    private Authentication guardianAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }

    private Authentication hospitalStaffAuthentication() {
        MemberPrincipal principal = new MemberPrincipal(2L, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }
}
