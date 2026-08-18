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
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
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
 * Level 2 — 셀프 복구(보호자)·정정 재청구(스태프) 엔드포인트의 인가 검증(고도화 3.3·3.5-a). SecurityConfig의 URL
 * 매처가 실제로 역할을 강제하는지 보려고 필터 체인을 켠 채(addFilters 기본값 true) 실행한다. 계약 테스트
 * (ReservationPaymentRechargeControllerTest·HospitalPaymentControllerTest, addFilters=false)가 다루지 못하는
 * "다른 역할이 남의 경로를 호출해 남의 카드로 청구" 회귀를 이 클래스가 막는다.
 *
 * <p>특히 recharge 경로 매처는 이번에 추가했다 — 없거나 경로 오타면 anyRequest().authenticated()로 떨어져
 * 스태프도 보호자 셀프 복구를 호출할 수 있게 된다. 그 회귀를 아래 staff→403 케이스가 잡는다.
 */
@WebMvcTest(controllers = {ReservationPaymentRechargeController.class, HospitalPaymentController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class PaymentRechargeCorrectionAuthorizationTest {

    private static final String RECHARGE_URL = "/api/reservations/100/payments/recharge";
    private static final String RECHARGE_BODY = "{\"paymentMethodId\":7}";
    private static final String CORRECTION_URL = "/api/hospital/reservations/100/payments/correction";
    private static final String CORRECTION_BODY = "{\"draftToken\":\"0123456789abcdef0123456789abcdef\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentApplicationService paymentApplicationService;

    @MockitoBean
    private PaymentQueryService paymentQueryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    // --- 셀프 복구: 보호자 전용 (POST /api/reservations/*/payments/recharge) ---

    @Test
    @DisplayName("보호자는 셀프 복구를 호출할 수 있다(인가 통과 → 201)")
    void recharge_guardian_allowed() throws Exception {
        given(paymentApplicationService.recharge(anyLong(), anyLong(), anyLong()))
                .willReturn(new PaymentChargeResponse(2L, 100L, PaymentStatus.PAID, 50000, "VISA", "1234", null));

        mockMvc.perform(post(RECHARGE_URL)
                        .with(authentication(guardian(5L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECHARGE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("병원 스태프는 셀프 복구 경로 접근이 거부된다(403) — 남의 카드로 재청구 방지")
    void recharge_staff_forbidden() throws Exception {
        mockMvc.perform(post(RECHARGE_URL)
                        .with(authentication(staff(9L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECHARGE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비인증 요청은 셀프 복구 접근이 거부된다(401)")
    void recharge_anonymous_unauthorized() throws Exception {
        mockMvc.perform(post(RECHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RECHARGE_BODY))
                .andExpect(status().isUnauthorized());
    }

    // --- 정정 재청구: 병원 스태프 전용 (POST /api/hospital/reservations/*/payments/correction) ---

    @Test
    @DisplayName("병원 스태프는 정정 재청구를 호출할 수 있다(인가 통과 → 201)")
    void correction_staff_allowed() throws Exception {
        given(paymentApplicationService.correctionCharge(anyLong(), anyLong(), anyString()))
                .willReturn(new PaymentChargeResponse(2L, 100L, PaymentStatus.PAID, 30000, "VISA", "1234", null));

        mockMvc.perform(post(CORRECTION_URL)
                        .with(authentication(staff(9L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORRECTION_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("보호자는 정정 재청구(병원 경로) 접근이 거부된다(403)")
    void correction_guardian_forbidden() throws Exception {
        mockMvc.perform(post(CORRECTION_URL)
                        .with(authentication(guardian(5L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORRECTION_BODY))
                .andExpect(status().isForbidden());
    }

    private Authentication guardian(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "guardian@example.com", "GUARDIAN");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_GUARDIAN")));
    }

    private Authentication staff(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }
}
