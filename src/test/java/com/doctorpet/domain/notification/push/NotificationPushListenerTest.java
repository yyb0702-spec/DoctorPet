package com.doctorpet.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.doctorpet.domain.notification.channel.NotificationChannel;
import com.doctorpet.domain.notification.channel.NotificationChannelType;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — 커밋 후 리스너가 등록된 모든 채널에 전달하고, 채널 하나의 실패가 나머지를 막지 않는지 검증한다
 * (고도화 3.9 — 채널이 둘 이상이 되면서 격리 단위를 채널로 좁혔다).
 *
 * <p>AFTER_COMMIT phase 자체는 {@code NotificationCommitPushIntegrationTest}(Level 3)가 실제 트랜잭션으로 본다.
 */
class NotificationPushListenerTest {

    private static final Long MEMBER_ID = 501L;

    private final NotificationResponse payload = new NotificationResponse(
            9L,
            NotificationType.PAYMENT_RESULT.name(),
            "진료비 결제가 완료되었습니다.",
            NotificationResourceType.PAYMENT.name(),
            3L,
            false,
            null,
            LocalDateTime.of(2026, 8, 21, 12, 0)
    );

    @Test
    @DisplayName("등록된 모든 채널에 같은 알림을 전달한다")
    void onNotificationCreated_deliversToEveryChannel() {
        NotificationChannel realtime = channel(NotificationChannelType.REALTIME);
        NotificationChannel email = channel(NotificationChannelType.EMAIL);
        NotificationPushListener listener = new NotificationPushListener(List.of(realtime, email));

        listener.onNotificationCreated(event());

        verify(realtime).deliver(
                eq(NotificationRecipientType.MEMBER), eq(MEMBER_ID), eq(NotificationType.PAYMENT_RESULT),
                eq(payload));
        verify(email).deliver(
                eq(NotificationRecipientType.MEMBER), eq(MEMBER_ID), eq(NotificationType.PAYMENT_RESULT),
                eq(payload));
    }

    @Test
    @DisplayName("한 채널의 전달 실패가 나머지 채널을 막지 않고 상위로 전파되지도 않는다")
    void onNotificationCreated_isolatesChannelFailure() {
        // 이메일(SMTP 장애)이 먼저 터져도 SSE 전달은 그대로 나가야 한다 — 저장은 이미 확정됐고,
        // 도달 가능한 채널까지 함께 잃으면 사용자는 앱을 열고 있는데도 화면이 갱신되지 않는다.
        NotificationChannel failing = channel(NotificationChannelType.EMAIL);
        doThrow(new IllegalStateException("SMTP 장애")).when(failing)
                .deliver(any(), any(), any(), any());
        NotificationChannel realtime = channel(NotificationChannelType.REALTIME);
        NotificationPushListener listener = new NotificationPushListener(List.of(failing, realtime));

        assertThatCode(() -> listener.onNotificationCreated(event())).doesNotThrowAnyException();

        verify(realtime).deliver(any(), any(), any(), any());
    }

    private NotificationChannel channel(NotificationChannelType type) {
        NotificationChannel channel = mock(NotificationChannel.class);
        when(channel.type()).thenReturn(type);
        return channel;
    }

    private NotificationCreatedEvent event() {
        return new NotificationCreatedEvent(
                NotificationRecipientType.MEMBER, MEMBER_ID, NotificationType.PAYMENT_RESULT, payload);
    }
}
