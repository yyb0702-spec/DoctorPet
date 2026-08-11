package com.doctorpet.domain.notification.push;

// NotificationPusher의 SSE 구현. 회원(MEMBER) 수신자는 memberId 키 레지스트리로 "notification" 이벤트를 전송한다(SA §9-8).

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SseNotificationPusher implements NotificationPusher {

    private static final String EVENT_NAME = "notification";

    private final SseEmitterRegistry registry;

    @Override
    public void push(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationResponse notification
    ) {
        // 병원(HOSPITAL) 수신의 실시간 fan-out은 후속 PR(SSE 병원 라우팅)에서 연다. 레지스트리가 memberId 키라
        // hospitalId로는 접속 스태프 세션을 찾을 수 없으므로 지금은 전송하지 않는다 — 병원 알림은 저장·폴링으로 도달한다.
        if (recipientType != NotificationRecipientType.MEMBER) {
            return;
        }
        registry.send(recipientId, EVENT_NAME, notification);
    }
}
