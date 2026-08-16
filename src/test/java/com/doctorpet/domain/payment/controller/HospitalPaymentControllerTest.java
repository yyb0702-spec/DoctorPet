package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentApplicationService;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import java.time.LocalDateTime;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
    private static final String DRAFT_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String CHARGE_BODY = "{\"draftToken\":\"" + DRAFT_TOKEN + "\"}";

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
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("청구 성공 시 201과 결제 결과를 반환하고, 인증된 스태프 id로 서비스를 호출한다")
    void charge_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(DRAFT_TOKEN)))
                .willReturn(new PaymentChargeResponse(1L, 100L, PaymentStatus.PAID, 50000, "VISA", "1234", null));

        // 요청 body가 없다 — 총액은 미리 작성된 초안 항목 합계로 서버가 산출한다(SA §9-4 청구 항목).
        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHARGE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.paymentId").value(1))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.amount").value(50000))
                .andExpect(jsonPath("$.data.cardLast4Snapshot").value("1234"));

        verify(paymentApplicationService).charge(100L, STAFF_MEMBER_ID, DRAFT_TOKEN);
    }

    @Test
    @DisplayName("승인 실패는 201과 status=OFFLINE_REQUIRED로 응답한다(게이트웨이 예외가 500으로 새지 않는다)")
    void charge_offlineRequired() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(DRAFT_TOKEN)))
                .willReturn(new PaymentChargeResponse(1L, 100L, PaymentStatus.OFFLINE_REQUIRED, 50000, "VISA", "1234", "NON_RETRIABLE"));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHARGE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OFFLINE_REQUIRED"))
                .andExpect(jsonPath("$.data.failureReason").value("NON_RETRIABLE"));
    }

    @Test
    @DisplayName("초안 항목이 없으면 서비스에서 PAYMENT_ITEM_REQUIRED(PAYMENT_015, 409)로 응답한다")
    void charge_withoutDraftItems() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(DRAFT_TOKEN)))
                .willThrow(new ServiceException(PaymentErrorCode.PAYMENT_ITEM_REQUIRED));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHARGE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_015"));
    }

    @Test
    @DisplayName("초안 항목 합계가 상한을 넘으면 서비스에서 INVALID_AMOUNT(PAYMENT_001, 400)로 응답한다")
    void charge_overMaxAmount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        given(paymentApplicationService.charge(eq(100L), eq(STAFF_MEMBER_ID), eq(DRAFT_TOKEN)))
                .willThrow(new ServiceException(PaymentErrorCode.INVALID_AMOUNT));

        mockMvc.perform(post(CHARGE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHARGE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_001"));
    }

    @Test
    @DisplayName("병원 결제 내역 조회는 200과 목록을 반환하고, 인증된 스태프 id로 자병원 조회를 호출한다(#47)")
    void getReservationPayments_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication(STAFF_MEMBER_ID));
        // 상태별 status↔paymentChannel 계약을 고정한다(PR #79 P2 리뷰): OFFLINE_REQUIRED는 정산 방식 미확정이라 채널이 null,
        // PAID는 BILLING_KEY, OFFLINE_PAID는 OFFLINE. 운영에서 나올 수 없는 조합(OFFLINE_REQUIRED+BILLING_KEY)을 쓰지 않는다.
        given(paymentQueryService.getForHospital(100L, STAFF_MEMBER_ID)).willReturn(List.of(
                new PaymentHistoryResponse(1L, 100L, PaymentStatus.OFFLINE_REQUIRED, null,
                        50000, "VISA", "1234",
                        LocalDateTime.now(), null, LocalDateTime.now(), null, null),
                new PaymentHistoryResponse(2L, 101L, PaymentStatus.PAID, PaymentChannel.BILLING_KEY,
                        30000, "VISA", "5678",
                        LocalDateTime.now(), LocalDateTime.now(), null, null, null),
                new PaymentHistoryResponse(3L, 102L, PaymentStatus.OFFLINE_PAID, PaymentChannel.OFFLINE,
                        20000, "VISA", "9012",
                        LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now(), null)));

        mockMvc.perform(get(CHARGE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].status").value("OFFLINE_REQUIRED"))
                .andExpect(jsonPath("$.data[0].cardLast4Snapshot").value("1234"))
                .andExpect(jsonPath("$.data[0].failedAt").exists())
                // OFFLINE_REQUIRED는 정산 방식이 미확정이라 paymentChannel이 null이어야 한다(계약 회귀 방지).
                .andExpect(jsonPath("$.data[0].paymentChannel").isEmpty())
                .andExpect(jsonPath("$.data[0].pgPaymentId").doesNotExist())
                // 내부 처리 분류 코드(failureReason)는 내역 응답에 노출하지 않는다(PR #79 리뷰 반영).
                .andExpect(jsonPath("$.data[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.data[1].status").value("PAID"))
                .andExpect(jsonPath("$.data[1].paymentChannel").value("BILLING_KEY"))
                .andExpect(jsonPath("$.data[1].paidAt").exists())
                .andExpect(jsonPath("$.data[2].status").value("OFFLINE_PAID"))
                .andExpect(jsonPath("$.data[2].paymentChannel").value("OFFLINE"))
                .andExpect(jsonPath("$.data[2].offlineSettledAt").exists());

        verify(paymentQueryService).getForHospital(100L, STAFF_MEMBER_ID);
    }

    private Authentication staffAuthentication(Long memberId) {
        MemberPrincipal principal = new MemberPrincipal(memberId, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }
}
