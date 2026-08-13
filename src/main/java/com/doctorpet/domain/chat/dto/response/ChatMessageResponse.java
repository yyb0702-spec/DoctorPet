package com.doctorpet.domain.chat.dto.response;

import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.entity.ChatSenderType;
import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long messageId,
        ChatSenderType senderType,
        String content,
        LocalDateTime createdAt,
        String senderName
) {

    public static ChatMessageResponse from(
            ChatMessage message,
            String hospitalName,
            String guardianNickname
    ) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSenderType(),
                message.getBody(),
                message.getCreatedAt(),
                message.getSenderType() == ChatSenderType.HOSPITAL ? hospitalName : guardianNickname
        );
    }
}
