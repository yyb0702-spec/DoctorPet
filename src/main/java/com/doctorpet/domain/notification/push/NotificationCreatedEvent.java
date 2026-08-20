package com.doctorpet.domain.notification.push;

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationType;

/**
 * 알림이 저장됐을 때 발행하는 도메인 이벤트. 커밋 이후 채널 전달을 트리거하기 위한 것으로,
 * 수신자 식별(recipientType + recipientId)은 응답 DTO에 노출하지 않으므로 이벤트에 별도로 싣는다(고도화 3.10).
 *
 * <p>알림 유형(type)도 enum 그대로 싣는다(고도화 3.9) — payload의 type은 문자열이라 채널이 대상 여부를 판단하려면
 * 다시 파싱해야 하고, 그러면 알 수 없는 값에서 조용히 깨진다. 채널 판단은 컴파일 타임에 고정된 enum으로 한다.
 */
public record NotificationCreatedEvent(
        NotificationRecipientType recipientType,
        Long recipientId,
        NotificationType type,
        NotificationResponse payload
) {
}
