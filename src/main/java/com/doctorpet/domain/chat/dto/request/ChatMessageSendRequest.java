package com.doctorpet.domain.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record ChatMessageSendRequest(
        @NotBlank @Size(max = 1000) String content,
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$") String clientMessageId
) {
    public ChatMessageSendRequest(String content) {
        this(content, UUID.randomUUID().toString());
    }
}
