package com.doctorpet.domain.chat.config;

import com.doctorpet.global.security.AccessTokenBlacklistPort;
import com.doctorpet.global.security.MemberBlacklistPort;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.PasswordChangeInvalidationPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * STOMP CONNECT에서 검증한 Access Token의 최소 식별 정보만 세션별로 보관한다.
 * 원문 JWT는 저장하지 않으며, 인바운드·아웃바운드 채팅 프레임마다 블랙리스트·만료를 다시 확인한다.
 */
@Component
@RequiredArgsConstructor
class ChatSessionAuthenticationStore {

    private final MemberBlacklistPort memberBlacklistPort;
    private final AccessTokenBlacklistPort accessTokenBlacklistPort;
    private final PasswordChangeInvalidationPort passwordChangeInvalidationPort;
    private final Clock applicationClock;
    private final ConcurrentMap<String, ChatSessionAuthentication> sessions = new ConcurrentHashMap<>();

    void register(
            StompHeaderAccessor accessor,
            Authentication authentication,
            String accessTokenJti,
            Instant expiresAt,
            Date issuedAt
    ) {
        String sessionId = requireSessionId(accessor);
        if (!(authentication.getPrincipal() instanceof MemberPrincipal principal)) {
            throw new AccessDeniedException("인증되지 않은 STOMP 요청입니다.");
        }
        sessions.put(sessionId, new ChatSessionAuthentication(principal, accessTokenJti, expiresAt, issuedAt));
    }

    Authentication requireUsableAuthentication(StompHeaderAccessor accessor) {
        String sessionId = requireSessionId(accessor);
        ChatSessionAuthentication session = sessions.get(sessionId);
        // 비밀번호 재설정 이전에 발급된 Access Token 차단(리뷰 지적) — CONNECT 시점 authenticate()
        // 뿐 아니라 이후 매 프레임 재검사에도 같은 기준을 적용해야, CONNECT 직후 비밀번호가
        // 재설정된 세션이 만료 전까지 계속 채팅을 읽고 보낼 수 있는 구멍을 막는다.
        if (session == null || !session.expiresAt().isAfter(applicationClock.instant())
                || memberBlacklistPort.isBlacklisted(session.principal().memberId())
                || accessTokenBlacklistPort.isBlacklisted(session.accessTokenJti())
                || passwordChangeInvalidationPort.isTokenInvalidatedByPasswordChange(
                        session.principal().memberId(), session.issuedAt())) {
            sessions.remove(sessionId);
            throw new AccessDeniedException("사용할 수 없는 STOMP Access Token입니다.");
        }
        return new UsernamePasswordAuthenticationToken(session.principal(), null);
    }

    boolean hasSession(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        return sessionId != null && sessions.containsKey(sessionId);
    }

    void remove(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @EventListener
    void removeDisconnectedSession(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }

    private String requireSessionId(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            throw new AccessDeniedException("인증되지 않은 STOMP 요청입니다.");
        }
        return sessionId;
    }

    private record ChatSessionAuthentication(
            MemberPrincipal principal,
            String accessTokenJti,
            Instant expiresAt,
            Date issuedAt
    ) {
    }
}
