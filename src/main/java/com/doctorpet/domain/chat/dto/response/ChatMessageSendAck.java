package com.doctorpet.domain.chat.dto.response;

/** 같은 clientMessageId의 재전송도 같은 저장 결과로 확인시키는 개인 STOMP ACK다. */
public record ChatMessageSendAck(String clientMessageId, Long messageId) {
}
