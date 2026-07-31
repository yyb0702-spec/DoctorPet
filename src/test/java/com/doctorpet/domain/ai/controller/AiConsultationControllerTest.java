package com.doctorpet.domain.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.model.AiStructuredResult;
import com.doctorpet.domain.ai.service.AiConsultationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AiConsultationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class AiConsultationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiConsultationService service;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 상담은 인증된 memberId로 호출하고 구조화 응답과 disclaimer를 반환한다")
    void consult_authenticated_success() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new MemberPrincipal(7L, "member@example.com", "GUARDIAN"), null, List.of()));
        AiConsultationResponse response = successResponse();
        given(service.consult(eq(7L), any(AiConsultationRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symptomText":"어제부터 밥을 안 먹어요","species":"DOG"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.structured.urgencyLevel").value("MODERATE"))
                .andExpect(jsonPath("$.data.hospitals").isArray())
                .andExpect(jsonPath("$.data.disclaimer").isNotEmpty())
                .andExpect(jsonPath("$.data.fallback").value(false));

        verify(service).consult(eq(7L), any(AiConsultationRequest.class));
    }

    @Test
    @DisplayName("비로그인 상담은 null memberId로 서비스를 호출한다")
    void consult_anonymous_passesNullMemberId() throws Exception {
        given(service.consult(eq(null), any(AiConsultationRequest.class))).willReturn(successResponse());

        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symptomText":"기침을 해요","species":"CAT"}
                                """))
                .andExpect(status().isOk());

        verify(service).consult(eq(null), any(AiConsultationRequest.class));
    }

    @Test
    @DisplayName("공백 증상은 400 COMMON_001로 거부한다")
    void consult_blankSymptom_rejected() throws Exception {
        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symptomText":"   ","species":"DOG"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("1,000자를 초과한 증상은 400으로 거부한다")
    void consult_tooLongSymptom_rejected() throws Exception {
        String body = "{\"symptomText\":\"" + "가".repeat(1001) + "\",\"species\":\"DOG\"}";

        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("DOG/CAT 밖의 축종은 400으로 거부한다")
    void consult_invalidSpecies_rejected() throws Exception {
        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symptomText":"아파요","species":"BIRD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private AiConsultationResponse successResponse() {
        return new AiConsultationResponse(
                new AiStructuredResult(
                        List.of("GENERAL"),
                        List.of("BLOOD_TEST"),
                        UrgencyLevel.MODERATE,
                        List.of("증상 시작 시점"),
                        true
                ),
                List.of(),
                "AI 분석은 참고용입니다.",
                "진료역량을 정리했습니다.",
                false
        );
    }
}
