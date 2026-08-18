package com.doctorpet.domain.review.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.review.dto.response.ReviewPageResponse;
import com.doctorpet.domain.review.dto.response.HospitalReviewItemResponse;
import com.doctorpet.domain.review.service.ReviewApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = HospitalReviewController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class HospitalReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewApplicationService reviewApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @Test
    @DisplayName("비회원도 병원의 리뷰 목록을 조회할 수 있다")
    void getHospitalReviews_unauthenticated_returnsOk() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        HospitalReviewItemResponse review = new HospitalReviewItemResponse(
                100L,
                new BigDecimal("4.5"),
                "친절했어요.",
                now,
                now
        );
        given(reviewApplicationService.getHospitalReviews(3L, 1, 20))
                .willReturn(new ReviewPageResponse(
                        List.of(review), 1, 20, 1, 1, true, true
                ));

        mockMvc.perform(get("/api/hospitals/{hospitalId}/reviews", 3L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.content[0].reviewId").value(100L))
                .andExpect(jsonPath("$.data.content[0].reservationId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].hospitalId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].memberId").doesNotExist());
    }

    @Test
    @DisplayName("리뷰 목록 페이지 크기는 100을 초과할 수 없다")
    void getHospitalReviews_sizeOverMaximum_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/hospitals/{hospitalId}/reviews", 3L)
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }
}
