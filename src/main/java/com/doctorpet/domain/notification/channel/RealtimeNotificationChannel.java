package com.doctorpet.domain.notification.channel;

// 기존 SSE 실시간 전송을 채널 추상화에 얹는 어댑터(고도화 3.9).

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationChannelType;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.push.NotificationPusher;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * SSE 실시간 전송 채널. 구현을 새로 만들지 않고 기존 {@link NotificationPusher}(SseNotificationPusher·티켓 인증·
 * heartbeat·회원당 연결 상한)에 그대로 위임한다 — 알림 실시간은 단방향 SSE로 확정된 계약이라(AGENTS 확정 결정,
 * SA §9-8) 채널 추상화를 도입하면서 그 계약을 건드리지 않기 위해 어댑터로 감싼다.
 *
 * <p>그래서 {@code NotificationPusher.push}와 이 클래스의 {@code deliver}는 사실상 같은 일을 한다. 시그니처가
 * 겹치는 것은 의도된 것이다 — 새 채널(이메일 등)이 SSE 인프라 인터페이스를 구현하도록 강요하지 않으려면 전달
 * 계약과 SSE 계약을 분리해 둬야 한다.
 *
 * <p>대상 판단(MEMBER 직접 전송 / HOSPITAL은 소속 스태프 fan-out)은 그대로 pusher가 한다.
 */
@Component
// 가장 먼저 전달한다(리뷰 지적 P1). 인메모리 전송이라 빠르고, 앱을 보고 있는 사용자에게 즉시 도달해야 한다 —
// 외부 채널(이메일)을 먼저 부르면 그 왕복이 끝날 때까지 이 전달과 요청 응답이 함께 막힌다.
@Order(NotificationChannel.REALTIME_ORDER)
@RequiredArgsConstructor
public class RealtimeNotificationChannel implements NotificationChannel {

    private final NotificationPusher pusher;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.REALTIME;
    }

    @Override
    public void deliver(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationType type,
            NotificationResponse notification
    ) {
        // 실시간 전송은 알림 유형과 무관하게 전부 보낸다(인앱 화면의 배지·목록과 같은 범위) — 회원 수신 설정의
        // 대상도 아니다. 앱을 보고 있는 사용자에게 지금 화면을 갱신해 주는 것이 이 채널의 역할이다.
        pusher.push(recipientType, recipientId, notification);
    }
}
