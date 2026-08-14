package com.doctorpet.domain.chat.service;

import com.doctorpet.domain.chat.entity.ChatSenderType;

record ChatParticipant(
        ChatSenderType senderType,
        Long hospitalId
) {
}
