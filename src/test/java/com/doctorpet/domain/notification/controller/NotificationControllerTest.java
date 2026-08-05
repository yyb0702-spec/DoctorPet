package com.doctorpet.domain.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.notification.dto.response.NotificationPageResponse;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.service.NotificationService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class NotificationControllerTest {

    private static final Long MEMBER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

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

    private void authenticateAs(Long memberId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new MemberPrincipal(memberId, "member@example.com", "GUARDIAN"), null, List.of()));
    }

    @Test
    @DisplayName("목록 조회: 인증된 본인 memberId로 조회하고 유형별 연결정보를 포함해 반환한다")
    void getMyNotifications_returnsResourceInfo() throws Exception {
        authenticateAs(MEMBER_ID);
        NotificationResponse item = new NotificationResponse(
                100L, "PAYMENT_RESULT", "진료비 결제가 완료되었습니다.",
                "PAYMENT", 55L, false, null, LocalDateTime.of(2026, 8, 3, 10, 0));
        given(notificationService.getMyNotifications(eq(MEMBER_ID), any(), eq(0), eq(20)))
                .willReturn(new NotificationPageResponse(List.of(item), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].id").value(100))
                .andExpect(jsonPath("$.data.content[0].type").value("PAYMENT_RESULT"))
                .andExpect(jsonPath("$.data.content[0].resourceType").value("PAYMENT"))
                .andExpect(jsonPath("$.data.content[0].resourceId").value(55))
                .andExpect(jsonPath("$.data.content[0].isRead").value(false));
    }

    @Test
    @DisplayName("목록 조회: isRead 필터와 페이징 파라미터를 서비스로 전달한다")
    void getMyNotifications_passesFilterAndPaging() throws Exception {
        authenticateAs(MEMBER_ID);
        given(notificationService.getMyNotifications(eq(MEMBER_ID), eq(true), eq(1), eq(5)))
                .willReturn(new NotificationPageResponse(List.of(), 1, 5, 0, 0, false, true));

        mockMvc.perform(get("/api/notifications")
                        .param("isRead", "true")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(notificationService).getMyNotifications(MEMBER_ID, true, 1, 5);
    }

    @Test
    @DisplayName("읽음 처리: 200과 SUCCESS를 반환하고 인증 memberId로 위임한다")
    void markAsRead_success() throws Exception {
        authenticateAs(MEMBER_ID);

        mockMvc.perform(patch("/api/notifications/{id}/read", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(notificationService).markAsRead(MEMBER_ID, 100L);
    }

    @Test
    @DisplayName("읽음 처리: 타 사용자 알림이면 403")
    void markAsRead_forbidden() throws Exception {
        authenticateAs(MEMBER_ID);
        doThrow(new ServiceException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED))
                .when(notificationService).markAsRead(MEMBER_ID, 100L);

        mockMvc.perform(patch("/api/notifications/{id}/read", 100L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_002"));
    }

    @Test
    @DisplayName("읽음 처리: 없는 알림이면 404")
    void markAsRead_notFound() throws Exception {
        authenticateAs(MEMBER_ID);
        doThrow(new ServiceException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
                .when(notificationService).markAsRead(MEMBER_ID, 999L);

        mockMvc.perform(patch("/api/notifications/{id}/read", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_001"));
    }
}
