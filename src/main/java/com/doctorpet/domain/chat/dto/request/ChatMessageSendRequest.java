package com.doctorpet.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageSendRequest(
        @NotBlank @Size(max = 1000) String content,
        String clientMessageId
) {
    public ChatMessageSendRequest(String content) {
        this(content, null);
    }
}
