package com.doctorpet.domain.chat.controller;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.dto.response.ChatMessageSendAck;
import com.doctorpet.domain.chat.dto.response.ChatSubscriptionReady;
import com.doctorpet.domain.chat.service.ChatMessageService;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatMessageService chatMessageService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat/reservations/{reservationId}/messages")
    public void send(
            @DestinationVariable Long reservationId,
            @Valid @Payload ChatMessageSendRequest request,
            java.security.Principal principal,
            SimpMessageHeaderAccessor requestHeaders
    ) {
        if (!(principal instanceof org.springframework.security.core.Authentication authentication)
                || !(authentication.getPrincipal() instanceof MemberPrincipal memberPrincipal)) {
            throw new AccessDeniedException("인증되지 않은 STOMP 요청입니다.");
        }
        var response = chatMessageService.send(reservationId, memberPrincipal, request);
        String sessionId = requestHeaders.getSessionId();
        SimpMessageHeaderAccessor ackHeaders = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        ackHeaders.setSessionId(sessionId);
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/chat/send-acks",
                new ChatMessageSendAck(request.clientMessageId(), response.messageId()),
                ackHeaders.getMessageHeaders()
        );
    }

    @MessageMapping("/chat/reservations/{reservationId}/subscription-ready")
    @SendTo("/topic/chat/reservations/{reservationId}")
    public ChatSubscriptionReady confirmSubscription(
            @DestinationVariable Long reservationId,
            java.security.Principal principal
    ) {
        if (!(principal instanceof org.springframework.security.core.Authentication authentication)
                || !(authentication.getPrincipal() instanceof MemberPrincipal memberPrincipal)) {
            throw new AccessDeniedException("인증되지 않은 STOMP 요청입니다.");
        }
        chatMessageService.assertAccessible(reservationId, memberPrincipal);
        return ChatSubscriptionReady.forReservation(reservationId);
    }
}
