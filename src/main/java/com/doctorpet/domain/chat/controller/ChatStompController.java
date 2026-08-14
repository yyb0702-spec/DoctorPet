package com.doctorpet.domain.chat.controller;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.service.ChatMessageService;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatMessageService chatMessageService;

    @MessageMapping("/chat/reservations/{reservationId}/messages")
    public void send(
            @DestinationVariable Long reservationId,
            @Valid @Payload ChatMessageSendRequest request,
            java.security.Principal principal
    ) {
        if (!(principal instanceof org.springframework.security.core.Authentication authentication)
                || !(authentication.getPrincipal() instanceof MemberPrincipal memberPrincipal)) {
            throw new AccessDeniedException("인증되지 않은 STOMP 요청입니다.");
        }
        chatMessageService.send(reservationId, memberPrincipal, request);
    }
}
