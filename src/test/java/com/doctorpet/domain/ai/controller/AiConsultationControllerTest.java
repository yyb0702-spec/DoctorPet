package com.doctorpet.domain.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.exception.AiErrorCode;
import com.doctorpet.domain.ai.model.AiStructuredResult;
import com.doctorpet.domain.ai.service.AiConsultationService;
import com.doctorpet.domain.ai.service.AiRateLimiter;
import com.doctorpet.domain.ai.support.AiClientIpResolver;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
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
    private AiRateLimiter rateLimiter;

    @MockitoBean
    private AiClientIpResolver clientIpResolver;

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
                .andExpect(jsonPath("$.data.fallback").value(false))
                .andExpect(jsonPath("$.data.locationRecommended").value(false));

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
    @DisplayName("AI 상담 요청 한도를 초과하면 429 AI_001을 반환한다")
    void consult_rateLimitExceeded_returnsTooManyRequests() throws Exception {
        given(clientIpResolver.resolve(any())).willReturn("203.0.113.10");
        doThrow(new ServiceException(AiErrorCode.RATE_LIMIT_EXCEEDED))
                .when(rateLimiter).check(null, "203.0.113.10");

        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symptomText":"기침을 해요","species":"CAT"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_001"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("응급 위치 권장 응답은 locationRecommended를 true로 반환한다")
    void consult_emergencyWithoutLocation_returnsLocationRecommended() throws Exception {
        AiConsultationResponse response = new AiConsultationResponse(
                new AiStructuredResult(
                        List.of(),
                        List.of(),
                        UrgencyLevel.HIGH,
                        List.of("증상 시작 시점"),
                        true
                ),
                List.of(),
                "AI 분석은 참고용입니다.",
                "즉시 병원에 방문해 주세요.",
                false,
                true
        );
        given(service.consult(eq(null), any(AiConsultationRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"symptomText":"호흡이 어려워요","species":"DOG"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationRecommended").value(true));
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

    @Test
    @DisplayName("위도와 경도는 함께 입력해야 한다")
    void consult_incompleteCoordinates_rejected() throws Exception {
        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symptomText":"가까운 병원을 찾아줘",
                                  "species":"DOG",
                                  "latitude":37.5665
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("위도와 경도의 허용 범위를 벗어나면 거부한다")
    void consult_outOfRangeCoordinates_rejected() throws Exception {
        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symptomText":"가까운 병원을 찾아줘",
                                  "species":"DOG",
                                  "latitude":91,
                                  "longitude":181
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("유효한 좌표는 상담 요청으로 전달한다")
    void consult_validCoordinates_accepted() throws Exception {
        given(service.consult(eq(null), any(AiConsultationRequest.class)))
                .willReturn(successResponse());

        mockMvc.perform(post("/api/ai/consultations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symptomText":"가까운 병원을 찾아줘",
                                  "species":"DOG",
                                  "latitude":37.5665,
                                  "longitude":126.9780
                                }
                                """))
                .andExpect(status().isOk());

        verify(service).consult(eq(null), any(AiConsultationRequest.class));
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
                false,
                false
        );
    }
}
