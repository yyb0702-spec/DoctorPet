package com.doctorpet.domain.notification.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.service.NotificationSubscriptionService;
import com.doctorpet.global.config.SecurityConfig;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.JwtAccessDeniedHandler;
import com.doctorpet.global.security.JwtAuthenticationEntryPoint;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Level 2 — SSE 구독 엔드포인트의 인가 경계와 실패 응답 계약을 검증한다(PR #106).
 *
 * <p>구독 경로는 `EventSource`가 헤더 인증을 못 해 permitAll이므로, 실제 {@link SecurityConfig}를 그대로
 * 불러와 인증 없이도 401/403으로 막히지 않는지 확인한다. 이 핸들러는 더 이상 {@code produces}를 선언하지
 * 않는다 — 선언해두면 `Accept: text/event-stream`인 요청에서 예외가 났을 때 content negotiation이
 * `ApiResponse`(JSON)를 협상하지 못해 오류가 전달되지 않는 문제가 있었다(2차 리뷰 발견). 그래서 이
 * 테스트는 정상 응답이 여전히 `text/event-stream`인지(Spring이 `SseEmitter` 반환 타입으로 자동 결정),
 * 그리고 티켓 실패(401)·연결 상한(429) 오류는 Accept가 `text/event-stream`이어도 JSON으로 전달되는지를
 * 함께 검증한다.
 */
@WebMvcTest(controllers = NotificationSubscriptionController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
class NotificationSubscriptionControllerTest {

    private static final String SUBSCRIBE_PATH = "/api/notifications/subscribe";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationSubscriptionService subscriptionService;

    // SecurityConfig의 필터 체인 생성에 필요한 의존성들
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private MemberBlacklistPort memberBlacklistPort;

    @MockitoBean
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @MockitoBean
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort; // 기능 구멍 점검 대응(비밀번호 재설정 시 Access Token 무효화) - JwtAuthenticationFilter 생성자 의존성

    @Test
    @DisplayName("구독 경로는 인증 헤더가 없어도 401/403으로 막히지 않는다(EventSource가 헤더를 못 싣기 때문)")
    void subscribe_withoutAuthHeader_isNotBlockedByAuthentication() throws Exception {
        given(subscriptionService.subscribe(anyString()))
                .willThrow(new ServiceException(NotificationErrorCode.SSE_TICKET_INVALID));

        mockMvc.perform(get(SUBSCRIBE_PATH).param("ticket", "any-ticket"))
                .andExpect(status().isUnauthorized())
                // 인가 차단(403)이 아니라 서비스가 판단한 티켓 오류(401 NOTIFICATION_003)여야 한다.
                .andExpect(jsonPath("$.code").value(NotificationErrorCode.SSE_TICKET_INVALID.getCode()));
    }

    @Test
    @DisplayName("무효 티켓 오류는 Accept가 text/event-stream이어도 401 ApiResponse JSON으로 전달된다")
    void subscribe_invalidTicket_returnsJsonErrorEvenForEventStreamAccept() throws Exception {
        given(subscriptionService.subscribe(anyString()))
                .willThrow(new ServiceException(NotificationErrorCode.SSE_TICKET_INVALID));

        mockMvc.perform(get(SUBSCRIBE_PATH)
                        .param("ticket", "consumed-ticket")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(NotificationErrorCode.SSE_TICKET_INVALID.getCode()));
    }

    @Test
    @DisplayName("연결 상한 초과는 429 ApiResponse JSON으로 전달된다")
    void subscribe_atConnectionCap_returns429Json() throws Exception {
        given(subscriptionService.subscribe(anyString()))
                .willThrow(new ServiceException(NotificationErrorCode.SSE_TOO_MANY_CONNECTIONS));

        mockMvc.perform(get(SUBSCRIBE_PATH)
                        .param("ticket", "valid-ticket")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code")
                        .value(NotificationErrorCode.SSE_TOO_MANY_CONNECTIONS.getCode()));
    }

    @Test
    @DisplayName("유효한 티켓이면 응답 Content-Type이 text/event-stream이다(produces 제거 후에도 유지)")
    void subscribe_validTicket_respondsWithEventStream() throws Exception {
        given(subscriptionService.subscribe(anyString())).willReturn(new SseEmitter());

        mockMvc.perform(get(SUBSCRIBE_PATH)
                        .param("ticket", "valid-ticket")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    @DisplayName("유효한 티켓이면 nginx 버퍼링을 끄는 X-Accel-Buffering: no 헤더가 함께 내려간다(리뷰 지적)")
    void subscribe_validTicket_disablesProxyBuffering() throws Exception {
        given(subscriptionService.subscribe(anyString())).willReturn(new SseEmitter());

        mockMvc.perform(get(SUBSCRIBE_PATH)
                        .param("ticket", "valid-ticket")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"));
    }

    @Test
    @DisplayName("ticket 파라미터가 없거나 공백이면 400(VALIDATION_FAILED)으로 거절한다")
    void subscribe_withoutTicket_returns400() throws Exception {
        mockMvc.perform(get(SUBSCRIBE_PATH))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(SUBSCRIBE_PATH).param("ticket", "  "))
                .andExpect(status().isBadRequest());
    }
}
