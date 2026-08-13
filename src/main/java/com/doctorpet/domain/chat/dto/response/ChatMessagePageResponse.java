package com.doctorpet.domain.chat.dto.response;

import java.util.List;

public record ChatMessagePageResponse(
        List<ChatMessageResponse> messages,
        Long nextAfterMessageId,
        boolean hasNext
) {
}
