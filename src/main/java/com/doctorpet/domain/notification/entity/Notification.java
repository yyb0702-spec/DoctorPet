package com.doctorpet.domain.notification.entity;

// 저장형 알림 엔티티(SA §4 notifications, §9-8). 상태 전이 이벤트를 수신자별로 저장하고 폴링으로 조회한다.
// 수신자는 recipient_type(MEMBER|HOSPITAL) + recipient_id가 정본이다(고도화 3.10). member_id는 기존 행 백필과
// 하위호환용으로 유지하되(MEMBER 행만 채우고 HOSPITAL 행은 NULL), 신규 로직은 recipient_*를 기준으로 쓴다.
// 읽음 상태는 read_at(NULL=미읽음)을 정본으로 저장하고 isRead는 파생값이다(#39 확정).

import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
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
        indexes = {
                // 수신자(회원/병원) 최신순 목록 조회. recipient_type+recipient_id로 수신자를 특정한 뒤 created_at 정렬.
                @Index(
                        name = "idx_notifications_recipient_created",
                        columnList = "recipient_type, recipient_id, created_at"
                ),
                // 미읽음 개수·모두 읽음(#134)이 매 요청 (recipient_type, recipient_id) = ? AND read_at IS NULL로 훑는다.
                @Index(
                        name = "idx_notifications_recipient_read",
                        columnList = "recipient_type, recipient_id, read_at"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 수신자 정본. 기존 테이블에는 ddl-auto가 nullable로 추가하고, NotificationRecipientMigrationRunner가
    // MEMBER로 백필한 뒤 NOT NULL을 적용한다(엔티티에 nullable=false를 걸면 기존 행이 있는 운영 테이블에서
    // 컬럼 추가 자체가 실패하므로 제약은 Runner가 건다 — reservations.approval_deadline_at와 같은 관례).
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", length = 20)
    private NotificationRecipientType recipientType;

    @Column(name = "recipient_id")
    private Long recipientId;

    // 하위호환/백필용. HOSPITAL 수신 도입으로 회원 없는 알림이 생기므로 nullable이다. 신규 로직은 recipient_*를 쓴다.
    @Column(name = "member_id")
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
            NotificationRecipientType recipientType,
            Long recipientId,
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        this.recipientType = recipientType;
        this.recipientId = recipientId;
        this.memberId = memberId;
        this.type = type;
        this.content = content;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    // 수신자(타입+id)를 받는 정본 팩토리. MEMBER면 member_id도 함께 채워(백필/호환) 하위호환을 유지하고,
    // HOSPITAL이면 member_id는 NULL로 둔다.
    public static Notification create(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        Long memberId = recipientType == NotificationRecipientType.MEMBER ? recipientId : null;
        return new Notification(
                recipientType, recipientId, memberId, type, content, resourceType, resourceId);
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

    // 이 알림이 해당 수신자(타입+id)의 것인지 확인한다. 읽음 처리 시 소유권 재검증에 쓴다(회원↔병원 격리).
    public boolean isReceivedBy(NotificationRecipientType recipientType, Long recipientId) {
        return this.recipientType == recipientType && this.recipientId.equals(recipientId);
    }
}
