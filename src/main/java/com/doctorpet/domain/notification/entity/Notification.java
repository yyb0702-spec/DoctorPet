package com.doctorpet.domain.notification.entity;

// 저장형 알림 엔티티(SA §4 notifications, §9-8). 상태 전이 이벤트를 수신자별로 저장하고 폴링으로 조회한다.
// 읽음 상태는 read_at(NULL=미읽음)을 정본으로 저장하고 isRead는 파생값이다(#39 확정).

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "notifications",
        indexes = @Index(name = "idx_notifications_member_created", columnList = "member_id, created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 500)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", length = 20)
    private NotificationResourceType resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    private Notification(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        this.memberId = memberId;
        this.type = type;
        this.content = content;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public static Notification create(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        return new Notification(memberId, type, content, resourceType, resourceId);
    }

    // 최초 1회만 읽음 시각을 기록한다. 이미 읽은 알림에 다시 호출해도 시각이 바뀌지 않아 반복 요청이 멱등하다.
    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }
}
