package com.doctorpet.domain.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.chat.service.ChatMessageService;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import com.doctorpet.global.security.TokenType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class ChatChannelInterceptorTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private MemberBlacklistPort memberBlacklistPort;
    @Mock private AccessTokenBlacklistPort accessTokenBlacklistPort;
    @Mock private PasswordChangeInvalidationPort passwordChangeInvalidationPort;
    @Mock private ChatMessageService chatMessageService;

    private ChatChannelInterceptor interceptor;
    private ChatSessionAuthenticationStore sessionAuthenticationStore;

    @BeforeEach
    void setUp() {
        sessionAuthenticationStore = new ChatSessionAuthenticationStore(
                memberBlacklistPort, accessTokenBlacklistPort, passwordChangeInvalidationPort, Clock.systemUTC());
        interceptor = new ChatChannelInterceptor(
                jwtTokenProvider, memberBlacklistPort, accessTokenBlacklistPort, passwordChangeInvalidationPort,
                chatMessageService, sessionAuthenticationStore, Clock.systemUTC());
    }

    @Test
    @DisplayName("STOMP CONNECT는 Authorization Access Token으로만 MemberPrincipal을 등록한다")
    void connectAuthenticatesAccessTokenOnly() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti");
        given(jwtTokenProvider.getRemainingTtl("access-token")).willReturn(Duration.ofMinutes(30));

        Message<?> result = interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, "Bearer access-token"), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(((org.springframework.security.core.Authentication) accessor.getUser()).getPrincipal())
                .isEqualTo(principal);
    }

    @Test
    @DisplayName("Authorization 누락·위조 또는 만료 토큰·Refresh Token이면 CONNECT를 거부한다")
    void rejectsUnauthenticatedInvalidAndRefreshConnect() {
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, null), null))
                .isInstanceOf(AccessDeniedException.class);

        given(jwtTokenProvider.validateToken("invalid-or-expired-token")).willReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, "Bearer invalid-or-expired-token"), null))
                .isInstanceOf(AccessDeniedException.class);

        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("refresh-token")).willReturn(TokenType.REFRESH);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, "Bearer refresh-token"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("멤버 또는 Access Token 블랙리스트에 있으면 CONNECT를 거부한다")
    void rejectsBlacklistedAccessToken() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti");
        given(memberBlacklistPort.isBlacklisted(principal.memberId())).willReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, "Bearer access-token"), null))
                .isInstanceOf(AccessDeniedException.class);

        given(memberBlacklistPort.isBlacklisted(principal.memberId())).willReturn(false);
        given(accessTokenBlacklistPort.isBlacklisted("jti")).willReturn(true);
        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, "Bearer access-token"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("비밀번호 재설정 이전에 발급된 Access Token이면 CONNECT를 거부한다(리뷰 지적 — JwtAuthenticationFilter에만 있던 재설정 무효화가 STOMP 경로에도 적용돼야 함)")
    void rejectsPasswordChangeInvalidatedAccessToken() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Date issuedAt = new Date();
        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti");
        given(jwtTokenProvider.getIssuedAt("access-token")).willReturn(issuedAt);
        given(passwordChangeInvalidationPort.isTokenInvalidatedByPasswordChange(
                principal.memberId(), issuedAt)).willReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(
                stompMessage(StompCommand.CONNECT, null, "Bearer access-token"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("인증된 구독도 정본 destination만 허용하고 공통 서비스 인가를 호출한다")
    void subscribesOnlyToAuthorizedReservationDestination() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Message<?> authorized = connectedStompMessage(
                StompCommand.SUBSCRIBE, "/topic/chat/reservations/123", principal);

        interceptor.preSend(authorized, null);

        verify(chatMessageService).assertAccessible(123L, principal);
        assertThatThrownBy(() -> interceptor.preSend(connectedStompMessage(
                StompCommand.SUBSCRIBE, "/topic/chat/reservations/123/other", principal), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("CONNECT에서 인증한 주체를 서버 세션에 보관해 뒤이은 SUBSCRIBE에도 복원한다")
    void restoresConnectPrincipalFromServerSessionAttributes() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti");
        given(jwtTokenProvider.getRemainingTtl("access-token")).willReturn(Duration.ofMinutes(30));
        Map<String, Object> sessionAttributes = new HashMap<>();

        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session-1");
        connect.setSessionAttributes(sessionAttributes);
        connect.addNativeHeader("Authorization", "Bearer access-token");
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()), null);

        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("session-1");
        subscribe.setSessionAttributes(sessionAttributes);
        subscribe.setDestination("/topic/chat/reservations/123");
        Message<?> restored = interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()), null);

        verify(chatMessageService).assertAccessible(123L, principal);
        assertThat(((org.springframework.security.core.Authentication) StompHeaderAccessor.wrap(restored)
                .getUser()).getPrincipal()).isEqualTo(principal);
    }

    @Test
    @DisplayName("CONNECT 뒤 회원이 블랙리스트에 오르면 기존 세션의 구독과 전송을 차단한다")
    void blocksExistingSessionWhenMemberBecomesBlacklisted() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Message<?> subscribe = connectedStompMessage(
                StompCommand.SUBSCRIBE, "/topic/chat/reservations/123", principal);
        given(memberBlacklistPort.isBlacklisted(principal.memberId())).willReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(chatMessageService);
    }

    @Test
    @DisplayName("CONNECT 뒤 비밀번호가 재설정되면 기존 세션의 구독과 전송을 차단한다(리뷰 지적 — CONNECT 시점 검사만으로는 CONNECT 이후의 재설정을 못 막음)")
    void blocksExistingSessionWhenPasswordChangesAfterConnect() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Message<?> subscribe = connectedStompMessage(
                StompCommand.SUBSCRIBE, "/topic/chat/reservations/123", principal);
        given(passwordChangeInvalidationPort.isTokenInvalidatedByPasswordChange(eq(principal.memberId()), any()))
                .willReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(subscribe, null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(chatMessageService);
    }

    @Test
    @DisplayName("CONNECT 뒤 Access Token 만료 시 기존 세션의 구독과 전송을 차단한다")
    void blocksExistingSessionAfterAccessTokenExpires() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
        ChatSessionAuthenticationStore expiredSessionStore = new ChatSessionAuthenticationStore(
                memberBlacklistPort, accessTokenBlacklistPort, passwordChangeInvalidationPort, fixedClock);
        ChatChannelInterceptor expiredSessionInterceptor = new ChatChannelInterceptor(
                jwtTokenProvider, memberBlacklistPort, accessTokenBlacklistPort, passwordChangeInvalidationPort,
                chatMessageService, expiredSessionStore, fixedClock);
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Map<String, Object> sessionAttributes = new HashMap<>();
        given(jwtTokenProvider.validateToken("expired-after-connect-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("expired-after-connect-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("expired-after-connect-token")).willReturn(principal);
        given(jwtTokenProvider.getJti("expired-after-connect-token")).willReturn("expired-jti");
        given(jwtTokenProvider.getRemainingTtl("expired-after-connect-token")).willReturn(Duration.ZERO);

        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("expired-session");
        connect.setSessionAttributes(sessionAttributes);
        connect.addNativeHeader("Authorization", "Bearer expired-after-connect-token");
        expiredSessionInterceptor.preSend(
                MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()), null);

        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId("expired-session");
        subscribe.setSessionAttributes(sessionAttributes);
        subscribe.setDestination("/topic/chat/reservations/123");
        assertThatThrownBy(() -> expiredSessionInterceptor.preSend(
                MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(chatMessageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MESSAGE", "CONNECTED", "RECEIPT", "ERROR", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT"})
    @DisplayName("서버 전용 또는 트랜잭션 STOMP 명령은 인증된 클라이언트라도 차단한다")
    void rejectsCommandsThatCouldBypassChatSendAuthorization(String command) {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());

        assertThatThrownBy(() -> interceptor.preSend(authenticatedStompMessage(
                StompCommand.valueOf(command), "/topic/chat/reservations/123", principal), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(chatMessageService);
    }

    private Message<?> stompMessage(StompCommand command, String destination, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-" + command);
        accessor.setDestination(destination);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> authenticatedStompMessage(
            StompCommand command, String destination, MemberPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> connectedStompMessage(
            StompCommand command,
            String destination,
            MemberPrincipal principal
    ) {
        String accessToken = "access-token-" + command + '-' + destination;
        String sessionId = "session-" + command + '-' + destination;
        Map<String, Object> sessionAttributes = new HashMap<>();
        given(jwtTokenProvider.validateToken(accessToken)).willReturn(true);
        given(jwtTokenProvider.getTokenType(accessToken)).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal(accessToken)).willReturn(principal);
        given(jwtTokenProvider.getJti(accessToken)).willReturn("jti-" + command + '-' + destination);
        given(jwtTokenProvider.getRemainingTtl(accessToken)).willReturn(Duration.ofMinutes(30));

        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId(sessionId);
        connect.setSessionAttributes(sessionAttributes);
        connect.addNativeHeader("Authorization", "Bearer " + accessToken);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
