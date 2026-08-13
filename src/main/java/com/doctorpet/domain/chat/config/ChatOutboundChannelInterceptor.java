package com.doctorpet.domain.chat.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** 기존 구독에도 토큰 무효화·만료를 적용해, 무효 세션으로 채팅 MESSAGE가 전달되지 않게 한다. */
@Component
class ChatOutboundChannelInterceptor implements ChannelInterceptor {

    private static final String CHAT_TOPIC_PREFIX = "/topic/chat/reservations/";

    private final ChatSessionAuthenticationStore sessionAuthenticationStore;

    ChatOutboundChannelInterceptor(ChatSessionAuthenticationStore sessionAuthenticationStore) {
        this.sessionAuthenticationStore = sessionAuthenticationStore;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        // SimpleBroker가 clientOutboundChannel로 보내는 메시지는 STOMP command가 아니라
        // SimpMessageType.MESSAGE로 표현된다. command만 확인하면 기존 구독자로 가는
        // 브로커 발행 메시지가 검증을 우회한다.
        if (accessor.getMessageType() != SimpMessageType.MESSAGE
                || accessor.getDestination() == null
                || !accessor.getDestination().startsWith(CHAT_TOPIC_PREFIX)) {
            return message;
        }
        try {
            sessionAuthenticationStore.requireUsableAuthentication(accessor);
            return message;
        } catch (AccessDeniedException exception) {
            // 아웃바운드 경로에서 ERROR 프레임을 만들면 이미 무효화된 세션에 오류 메시지까지
            // 전달될 수 있다. 해당 구독자에게만 이번 MESSAGE를 전달하지 않는다.
            return null;
        }
    }
}
