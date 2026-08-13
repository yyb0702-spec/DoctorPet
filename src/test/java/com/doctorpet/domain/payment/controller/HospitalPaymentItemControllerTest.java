package com.doctorpet.domain.payment.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.payment.dto.response.PaymentItemResponse;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.service.PaymentItemCommand;
import com.doctorpet.domain.payment.service.PaymentItemDraftService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
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
 * Level 2 — 청구 항목 초안 API 계약(상태코드·ApiResponse 포맷·Validation·인증 주체) 검증(SA §9-4 청구 항목).
 * 필터는 addFilters=false로 끄고 인증 주체를 SecurityContext에 직접 주입한다(인가 URL 강제는 인가 테스트가 담당).
 */
@WebMvcTest(controllers = HospitalPaymentItemController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class HospitalPaymentItemControllerTest {

    private static final Long STAFF_MEMBER_ID = 9L;
    private static final String ITEMS_URL = "/api/hospital/reservations/100/payment-items";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PaymentItemDraftService paymentItemDraftService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private MemberBlacklistPort memberBlacklistPort;
    @MockitoBean private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("초안 전체 교체는 200과 저장된 항목을 반환하고, 항목 목록을 그대로 서비스에 전달한다(총액은 받지 않는다)")
    void replaceDrafts_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());
        List<PaymentItemCommand> expected = List.of(
                new PaymentItemCommand("진찰료", 1, 20000),
                new PaymentItemCommand("주사", 2, 15000),
                new PaymentItemCommand("재진 할인", 1, -5000));
        given(paymentItemDraftService.replaceDrafts(eq(100L), eq(STAFF_MEMBER_ID), eq(expected)))
                .willReturn(List.of(
                        new PaymentItemResponse("진찰료", 1, 20000, 20000),
                        new PaymentItemResponse("주사", 2, 15000, 30000),
                        new PaymentItemResponse("재진 할인", 1, -5000, -5000)));

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"name":"진찰료","quantity":1,"unitPrice":20000},
                                  {"name":"주사","quantity":2,"unitPrice":15000},
                                  {"name":"재진 할인","quantity":1,"unitPrice":-5000}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[1].amount").value(30000))
                // 할인 항목은 음수 금액으로 그대로 노출된다.
                .andExpect(jsonPath("$.data[2].amount").value(-5000));

        verify(paymentItemDraftService).replaceDrafts(100L, STAFF_MEMBER_ID, expected);
    }

    @Test
    @DisplayName("초안 조회는 200과 아직 청구되지 않은 항목 목록을 반환한다")
    void getDrafts_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());
        given(paymentItemDraftService.getDrafts(eq(100L), eq(STAFF_MEMBER_ID)))
                .willReturn(List.of(new PaymentItemResponse("진찰료", 1, 20000, 20000)));

        mockMvc.perform(get(ITEMS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("진찰료"))
                .andExpect(jsonPath("$.data[0].amount").value(20000));

        verify(paymentItemDraftService).getDrafts(100L, STAFF_MEMBER_ID);
    }

    @Test
    @DisplayName("항목 수량이 0 이하이면 400과 COMMON_001(VALIDATION_FAILED)을 반환하고 서비스를 호출하지 않는다")
    void replaceDrafts_nonPositiveQuantity() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"진료비\",\"quantity\":0,\"unitPrice\":50000}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(paymentItemDraftService);
    }

    @Test
    @DisplayName("항목 목록이 비어 있거나 없으면 400과 COMMON_001을 반환한다")
    void replaceDrafts_emptyOrMissingItems() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(paymentItemDraftService);
    }

    @Test
    @DisplayName("항목명이 비어 있으면 400과 COMMON_001을 반환한다")
    void replaceDrafts_blankItemName() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\" \",\"quantity\":1,\"unitPrice\":50000}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(paymentItemDraftService);
    }

    @Test
    @DisplayName("이미 청구된 예약이면 409와 PAYMENT_014(PAYMENT_ITEM_ALREADY_CHARGED)를 반환한다")
    void replaceDrafts_alreadyCharged() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());
        given(paymentItemDraftService.replaceDrafts(eq(100L), eq(STAFF_MEMBER_ID), org.mockito.ArgumentMatchers.any()))
                .willThrow(new ServiceException(PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED));

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"진료비\",\"quantity\":1,\"unitPrice\":50000}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_014"));
    }

    @Test
    @DisplayName("합계가 상한을 넘으면 400과 PAYMENT_001(INVALID_AMOUNT)을 반환한다")
    void replaceDrafts_overMaxTotal() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(staffAuthentication());
        given(paymentItemDraftService.replaceDrafts(eq(100L), eq(STAFF_MEMBER_ID), org.mockito.ArgumentMatchers.any()))
                .willThrow(new ServiceException(PaymentErrorCode.INVALID_AMOUNT));

        mockMvc.perform(put(ITEMS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"진료비\",\"quantity\":1,\"unitPrice\":3000001}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_001"));
    }

    private Authentication staffAuthentication() {
        MemberPrincipal principal =
                new MemberPrincipal(STAFF_MEMBER_ID, "staff@example.com", "HOSPITAL_STAFF");
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_HOSPITAL_STAFF")));
    }
}
