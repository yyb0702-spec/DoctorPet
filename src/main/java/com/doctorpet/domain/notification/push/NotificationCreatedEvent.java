package com.doctorpet.domain.notification.push;

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;

/**
 * 알림이 저장됐을 때 발행하는 도메인 이벤트. 커밋 이후 실시간 전송을 트리거하기 위한 것으로,
 * 수신자 식별(recipientType + recipientId)은 응답 DTO에 노출하지 않으므로 이벤트에 별도로 싣는다(고도화 3.10).
 */
public record NotificationCreatedEvent(
        NotificationRecipientType recipientType,
        Long recipientId,
        NotificationResponse payload
) {
}
