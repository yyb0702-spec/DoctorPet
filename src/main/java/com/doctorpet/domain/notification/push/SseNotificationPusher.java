package com.doctorpet.domain.notification.push;

// NotificationPusher의 SSE 구현. 수신자별 SseEmitter 레지스트리로 "notification" 이벤트를 전송한다(SA §9-8).

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SseNotificationPusher implements NotificationPusher {

    private static final String EVENT_NAME = "notification";

    private final SseEmitterRegistry registry;

    @Override
    public void push(Long recipientMemberId, NotificationResponse notification) {
        registry.send(recipientMemberId, EVENT_NAME, notification);
    }
}
