package com.doctorpet.domain.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.review.dto.request.ReviewRequest;
import com.doctorpet.domain.review.dto.response.ReviewResponse;
import com.doctorpet.domain.review.service.ReviewApplicationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ReviewController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReviewApplicationService reviewApplicationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @Test
    @DisplayName("보호자는 결제 완료된 본인 예약에 리뷰를 작성할 수 있다")
    void create_guardian_returnsCreated() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        given(reviewApplicationService.create(
                eq(1L),
                eq(10L),
                any(ReviewRequest.class)
        )).willReturn(new ReviewResponse(
                100L,
                10L,
                3L,
                1L,
                new BigDecimal("4.5"),
                "친절하게 진료해 주셨어요.",
                now,
                now
        ));

        mockMvc.perform(post("/api/reservations/{reservationId}/reviews", 10L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reviewId").value(100L))
                .andExpect(jsonPath("$.data.rating").value(4.5));
    }

    @Test
    @DisplayName("0.5 단위가 아닌 평점은 400으로 거부한다")
    void create_invalidRatingStep_returnsBadRequest() throws Exception {
        ReviewRequest request = new ReviewRequest(
                new BigDecimal("4.3"),
                "친절했어요."
        );

        mockMvc.perform(post("/api/reservations/{reservationId}/reviews", 10L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("공백 리뷰 내용은 400으로 거부한다")
    void create_blankContent_returnsBadRequest() throws Exception {
        ReviewRequest request = new ReviewRequest(
                new BigDecimal("4.5"),
                "   "
        );

        mockMvc.perform(post("/api/reservations/{reservationId}/reviews", 10L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("병원 스태프는 보호자 리뷰를 작성할 수 없다")
    void create_hospitalStaff_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/reservations/{reservationId}/reviews", 10L)
                        .with(authentication(memberAuthentication(1L, "HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("미인증 사용자는 리뷰를 작성할 수 없다")
    void create_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/reservations/{reservationId}/reviews", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("보호자는 본인이 작성한 리뷰의 평점과 내용을 수정할 수 있다")
    void update_guardian_returnsOk() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        ReviewRequest request = new ReviewRequest(
                new BigDecimal("5.0"),
                "수정한 리뷰입니다."
        );
        given(reviewApplicationService.update(
                eq(1L), eq(100L), any(ReviewRequest.class)
        )).willReturn(new ReviewResponse(
                100L, 10L, 3L, 1L, new BigDecimal("5.0"),
                "수정한 리뷰입니다.", now, now
        ));

        mockMvc.perform(put("/api/reviews/{reviewId}", 100L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rating").value(5.0))
                .andExpect(jsonPath("$.data.content").value("수정한 리뷰입니다."));
    }

    @Test
    @DisplayName("0.5 단위가 아닌 수정 평점은 거부한다")
    void update_invalidRatingStep_returnsBadRequest() throws Exception {
        ReviewRequest request = new ReviewRequest(
                new BigDecimal("4.3"),
                "수정한 리뷰입니다."
        );

        mockMvc.perform(put("/api/reviews/{reviewId}", 100L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("병원 스태프는 리뷰를 수정할 수 없다")
    void update_hospitalStaff_returnsForbidden() throws Exception {
        ReviewRequest request = new ReviewRequest(
                new BigDecimal("5.0"),
                "수정한 리뷰입니다."
        );

        mockMvc.perform(put("/api/reviews/{reviewId}", 100L)
                        .with(authentication(memberAuthentication(1L, "HOSPITAL_STAFF")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    @Test
    @DisplayName("보호자는 본인이 작성한 리뷰를 삭제할 수 있다")
    void delete_guardian_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/reviews/{reviewId}", 100L)
                        .with(authentication(memberAuthentication(1L, "GUARDIAN"))))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("병원 스태프는 리뷰를 삭제할 수 없다")
    void delete_hospitalStaff_returnsForbidden() throws Exception {
        mockMvc.perform(delete("/api/reviews/{reviewId}", 100L)
                        .with(authentication(memberAuthentication(1L, "HOSPITAL_STAFF"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_003"));
    }

    private ReviewRequest validRequest() {
        return new ReviewRequest(
                new BigDecimal("4.5"),
                "친절하게 진료해 주셨어요."
        );
    }

    private Authentication memberAuthentication(Long memberId, String role) {
        MemberPrincipal principal = new MemberPrincipal(
                memberId,
                "guardian@example.com",
                role
        );
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }
}
