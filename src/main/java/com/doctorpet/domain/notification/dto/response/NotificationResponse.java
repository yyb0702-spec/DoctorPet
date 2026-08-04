package com.doctorpet.domain.notification.dto.response;

// 알림 단건 응답. read_at 정본에서 isRead를 파생해 노출하고, 연결 리소스는 generic 참조(type+id)로 내려준다.

import com.doctorpet.domain.notification.entity.Notification;
import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        String type,
        String content,
        String resourceType,
        Long resourceId,
        boolean isRead,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getContent(),
                notification.getResourceType() == null ? null : notification.getResourceType().name(),
                notification.getResourceId(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
