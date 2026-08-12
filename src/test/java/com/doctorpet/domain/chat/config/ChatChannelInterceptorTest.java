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
import com.doctorpet.global.security.TokenType;
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
    @Mock private ChatMessageService chatMessageService;

    private ChatChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ChatChannelInterceptor(
                jwtTokenProvider, memberBlacklistPort, accessTokenBlacklistPort, chatMessageService);
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
    @DisplayName("인증된 구독도 정본 destination만 허용하고 공통 서비스 인가를 호출한다")
    void subscribesOnlyToAuthorizedReservationDestination() {
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Message<?> authorized = authenticatedStompMessage(
                StompCommand.SUBSCRIBE, "/topic/chat/reservations/123", principal);

        interceptor.preSend(authorized, null);

        verify(chatMessageService).assertAccessible(123L, principal);
        assertThatThrownBy(() -> interceptor.preSend(authenticatedStompMessage(
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
}
