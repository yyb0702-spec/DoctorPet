package com.doctorpet.domain.chat.config;

import com.doctorpet.domain.chat.service.ChatMessageService;
import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ChatChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern SUBSCRIBE_DESTINATION = Pattern.compile(
            "^/topic/chat/reservations/(\\d+)$");
    private static final String SEND_ACK_DESTINATION = "/user/queue/chat/send-acks";
    private static final Pattern SEND_DESTINATION = Pattern.compile(
            "^/app/chat/reservations/(\\d+)/messages$");
    private static final Pattern SUBSCRIPTION_READY_DESTINATION = Pattern.compile(
            "^/app/chat/reservations/(\\d+)/subscription-ready$");
    private static final String AUTHENTICATED_USER_ATTRIBUTE =
            ChatChannelInterceptor.class.getName() + ".authenticatedUser";

    private final JwtTokenProvider jwtTokenProvider;
    private final MemberBlacklistPort memberBlacklistPort;
    private final AccessTokenBlacklistPort accessTokenBlacklistPort;
    private final ChatMessageService chatMessageService;
    private final ChatSessionAuthenticationStore sessionAuthenticationStore;
    private final Clock applicationClock;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            // Heartbeat도 CONNECT 뒤 세션이면 블랙리스트·만료를 다시 확인한다. command가 없는
            // 최초 프레임은 인증 상태가 없으므로 그대로 통과시킨다.
            if (sessionAuthenticationStore.hasSession(accessor)) {
                sessionAuthenticationStore.requireUsableAuthentication(accessor);
            }
            return message;
        }
        if (command == StompCommand.CONNECT) {
            AuthenticatedSession authenticatedSession = authenticate(accessor);
            Authentication authentication = authenticatedSession.authentication();
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                sessionAttributes.put(AUTHENTICATED_USER_ATTRIBUTE, authentication);
            }
            sessionAuthenticationStore.register(
                    accessor,
                    authentication,
                    authenticatedSession.accessTokenJti(),
                    authenticatedSession.expiresAt()
            );
            // wrap()으로 얻은 accessor에 user를 설정한 뒤 원 Message를 그대로 반환하면,
            // STOMP 세션에 Principal 변경이 반영되지 않을 수 있다. CONNECT 뒤 프레임도 같은
            // MemberPrincipal로 인가되도록 변경된 헤더의 Message를 명시적으로 반환한다.
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }
        if (command == StompCommand.SUBSCRIBE) {
            if (SEND_ACK_DESTINATION.equals(accessor.getDestination())) {
                requireAuthenticatedUser(accessor);
                return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
            }
            authorizeDestination(accessor, SUBSCRIBE_DESTINATION);
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }
        if (command == StompCommand.SEND) {
            String destination = accessor.getDestination();
            if (destination != null && SEND_DESTINATION.matcher(destination).matches()) {
                authorizeDestination(accessor, SEND_DESTINATION);
            } else {
                authorizeDestination(accessor, SUBSCRIPTION_READY_DESTINATION);
            }
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }
        if (command == StompCommand.DISCONNECT) {
            // 로그아웃·탈퇴·만료로 이미 무효화된 세션도 정상 종료는 허용해 메모리와 구독을 즉시 정리한다.
            sessionAuthenticationStore.remove(accessor);
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }
        if (command == StompCommand.UNSUBSCRIBE) {
            requireAuthenticatedUser(accessor);
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }
        // MESSAGE·CONNECTED·ERROR 등은 broker/server가 만드는 프레임이다. 클라이언트가
        // SimpleBroker topic에 직접 주입하면 저장·예약 관계 인가·AFTER_COMMIT를 모두 우회하므로
        // 명시적으로 거부한다.
        throw new AccessDeniedException("허용되지 않은 채팅 STOMP 명령입니다.");
    }

    private AuthenticatedSession authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new AccessDeniedException("STOMP CONNECT Authorization 헤더가 필요합니다.");
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (!jwtTokenProvider.validateToken(token)
                || jwtTokenProvider.getTokenType(token) != TokenType.ACCESS) {
            throw new AccessDeniedException("유효하지 않은 STOMP Access Token입니다.");
        }
        MemberPrincipal principal = jwtTokenProvider.getMemberPrincipal(token);
        String accessTokenJti = jwtTokenProvider.getJti(token);
        if (memberBlacklistPort.isBlacklisted(principal.memberId())
                || accessTokenBlacklistPort.isBlacklisted(accessTokenJti)) {
            throw new AccessDeniedException("사용할 수 없는 STOMP Access Token입니다.");
        }
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        accessor.setUser(authentication);
        return new AuthenticatedSession(
                authentication,
                accessTokenJti,
                Instant.now(applicationClock).plus(jwtTokenProvider.getRemainingTtl(token))
        );
    }

    private void authorizeDestination(StompHeaderAccessor accessor, Pattern destinationPattern) {
        restoreAuthenticatedUser(accessor);
        String destination = accessor.getDestination();
        if (!StringUtils.hasText(destination)) {
            throw new AccessDeniedException("허용되지 않은 채팅 STOMP destination입니다.");
        }
        Matcher matcher = destinationPattern.matcher(destination);
        if (!matcher.matches()) {
            throw new AccessDeniedException("허용되지 않은 채팅 STOMP destination입니다.");
        }
        Authentication authentication = requireAuthenticatedUser(accessor);
        MemberPrincipal principal = (MemberPrincipal) authentication.getPrincipal();
        chatMessageService.assertAccessible(Long.valueOf(matcher.group(1)), principal);
    }

    private Authentication requireAuthenticatedUser(StompHeaderAccessor accessor) {
        Authentication authentication = sessionAuthenticationStore.requireUsableAuthentication(accessor);
        accessor.setUser(authentication);
        return authentication;
    }

    private void restoreAuthenticatedUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() != null) {
            return;
        }
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return;
        }
        Object authenticatedUser = sessionAttributes.get(AUTHENTICATED_USER_ATTRIBUTE);
        if (authenticatedUser instanceof Authentication authentication
                && authentication.getPrincipal() instanceof MemberPrincipal) {
            accessor.setUser(authentication);
        }
    }

    private record AuthenticatedSession(
            Authentication authentication,
            String accessTokenJti,
            Instant expiresAt
    ) {
    }
}
