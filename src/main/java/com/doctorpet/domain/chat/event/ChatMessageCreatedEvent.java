package com.doctorpet.domain.chat.event;

import com.doctorpet.domain.chat.dto.response.ChatMessageResponse;

public record ChatMessageCreatedEvent(
        Long reservationId,
        ChatMessageResponse payload
) {
}
