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
import com.doctorpet.domain.notification.dto.response.NotificationReadAllResponse;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.service.NotificationRecipient;
import com.doctorpet.domain.notification.service.NotificationRecipientResolver;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private static final Long HOSPITAL_ID = 20L;
    private static final NotificationRecipient MEMBER_RECIPIENT =
            NotificationRecipient.member(MEMBER_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private NotificationRecipientResolver recipientResolver;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort; // #124 - JwtAuthenticationFilter 생성자 의존성

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @BeforeEach
    void resolveMemberByDefault() {
        // 기본은 회원(GUARDIAN) principal → (MEMBER, memberId). 병원 케이스는 개별 테스트에서 재정의한다.
        given(recipientResolver.resolve(any(MemberPrincipal.class))).willReturn(MEMBER_RECIPIENT);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long memberId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new MemberPrincipal(memberId, "member@example.com", role), null, List.of()));
    }

    @Test
    @DisplayName("목록 조회: 인증 principal을 해석한 수신자로 조회하고 유형별 연결정보를 포함해 반환한다")
    void getMyNotifications_returnsResourceInfo() throws Exception {
        authenticateAs(MEMBER_ID, "GUARDIAN");
        NotificationResponse item = new NotificationResponse(
                100L, "PAYMENT_RESULT", "진료비 결제가 완료되었습니다.",
                "PAYMENT", 55L, false, null, LocalDateTime.of(2026, 8, 3, 10, 0));
        given(notificationService.getMyNotifications(eq(MEMBER_RECIPIENT), any(), eq(0), eq(20)))
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
        authenticateAs(MEMBER_ID, "GUARDIAN");
        given(notificationService.getMyNotifications(eq(MEMBER_RECIPIENT), eq(true), eq(1), eq(5)))
                .willReturn(new NotificationPageResponse(List.of(), 1, 5, 0, 0, false, true));

        mockMvc.perform(get("/api/notifications")
                        .param("isRead", "true")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(notificationService).getMyNotifications(MEMBER_RECIPIENT, true, 1, 5);
    }

    @Test
    @DisplayName("목록 조회(병원 스태프): 해석된 (HOSPITAL, hospitalId) 수신자로 위임한다")
    void getMyNotifications_hospitalStaff() throws Exception {
        authenticateAs(MEMBER_ID, "HOSPITAL_STAFF");
        NotificationRecipient hospital = NotificationRecipient.hospital(HOSPITAL_ID);
        given(recipientResolver.resolve(any(MemberPrincipal.class))).willReturn(hospital);
        given(notificationService.getMyNotifications(eq(hospital), any(), eq(0), eq(20)))
                .willReturn(new NotificationPageResponse(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk());

        verify(notificationService).getMyNotifications(hospital, null, 0, 20);
    }

    @Test
    @DisplayName("미읽음 개수: 해석된 수신자로 위임하고 unreadCount를 SUCCESS로 반환한다")
    void getUnreadCount_returnsCount() throws Exception {
        authenticateAs(MEMBER_ID, "GUARDIAN");
        given(notificationService.getUnreadCount(MEMBER_RECIPIENT))
                .willReturn(new NotificationUnreadCountResponse(3L));

        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.unreadCount").value(3));

        verify(notificationService).getUnreadCount(MEMBER_RECIPIENT);
    }

    @Test
    @DisplayName("모두 읽음: 해석된 수신자로 위임하고 updatedCount를 SUCCESS로 반환한다")
    void markAllRead_returnsUpdatedCount() throws Exception {
        authenticateAs(MEMBER_ID, "GUARDIAN");
        given(notificationService.markAllRead(MEMBER_RECIPIENT))
                .willReturn(new NotificationReadAllResponse(4));

        mockMvc.perform(patch("/api/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.updatedCount").value(4));

        verify(notificationService).markAllRead(MEMBER_RECIPIENT);
    }

    @Test
    @DisplayName("읽음 처리: 200과 SUCCESS를 반환하고 해석된 수신자로 위임한다")
    void markAsRead_success() throws Exception {
        authenticateAs(MEMBER_ID, "GUARDIAN");

        mockMvc.perform(patch("/api/notifications/{id}/read", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(notificationService).markAsRead(MEMBER_RECIPIENT, 100L);
    }

    @Test
    @DisplayName("읽음 처리: 타 수신자 알림이면 403")
    void markAsRead_forbidden() throws Exception {
        authenticateAs(MEMBER_ID, "GUARDIAN");
        doThrow(new ServiceException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED))
                .when(notificationService).markAsRead(MEMBER_RECIPIENT, 100L);

        mockMvc.perform(patch("/api/notifications/{id}/read", 100L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_002"));
    }

    @Test
    @DisplayName("읽음 처리: 없는 알림이면 404")
    void markAsRead_notFound() throws Exception {
        authenticateAs(MEMBER_ID, "GUARDIAN");
        doThrow(new ServiceException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
                .when(notificationService).markAsRead(MEMBER_RECIPIENT, 999L);

        mockMvc.perform(patch("/api/notifications/{id}/read", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_001"));
    }
}
