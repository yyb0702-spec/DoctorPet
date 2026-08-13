package com.doctorpet.domain.chat.push;

import com.doctorpet.domain.chat.event.ChatMessageCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 저장 커밋이 끝난 채팅 메시지만 STOMP 구독자에게 전달한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessagePushListener {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChatMessageCreated(ChatMessageCreatedEvent event) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/chat/reservations/" + event.reservationId(), event.payload());
        } catch (RuntimeException e) {
            // 실시간 전달 실패는 커밋된 메시지 저장이나 예약 트랜잭션을 되돌리지 않는다.
            log.warn("채팅 실시간 전송 실패 reservationId={} messageId={}",
                    event.reservationId(), event.payload().messageId(), e);
        }
    }
}
