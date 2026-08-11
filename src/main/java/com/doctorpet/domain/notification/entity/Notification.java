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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notifications_member_created", columnList = "member_id, created_at"),
                // 미읽음 개수·모두 읽음(PR #134 리뷰)이 매 요청 member_id = ? AND read_at IS NULL로 훑는다.
                @Index(name = "idx_notifications_member_read", columnList = "member_id, read_at")
        },
        // 멱등 발행 전용 중복 방지 제약. 이름을 고정해, createIfAbsent가 이 제약의 중복 키 위반(DuplicateKeyException)만
        // 흡수하고 NOT NULL·길이 등 다른 무결성 오류는 전파하도록 근거로 삼는다(PR #139 리뷰 P2).
        uniqueConstraints = @UniqueConstraint(name = "uk_notifications_dedup_key", columnNames = "dedup_key")
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

    // 멱등 발행 전용 중복 방지 키. createIfAbsent로 저장하는 알림에만 (type:resourceType:resourceId:memberId)로 채우고
    // 일반 create는 null로 둔다. MySQL은 UNIQUE 인덱스에서 NULL을 서로 다르게 취급하므로, null인 일반 알림끼리는
    // 충돌하지 않고(예: PAID 후 REFUNDED의 PAYMENT_RESULT 정상 중복 허용) 멱등 발행만 (수신자·유형·리소스)당 1건으로
    // DB가 원자적으로 강제한다 — 존재조회→저장의 경합(락 밖 웹훅 vs 배치)에서도 중복 저장을 막는다. UNIQUE 제약은
    // @Table.uniqueConstraints에 uk_notifications_dedup_key로 이름을 붙였다(중복 키 위반만 선별 흡수하기 위함).
    @Column(name = "dedup_key", length = 200)
    private String dedupKey;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    private Notification(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId,
            String dedupKey
    ) {
        this.memberId = memberId;
        this.type = type;
        this.content = content;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.dedupKey = dedupKey;
    }

    public static Notification create(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        return new Notification(memberId, type, content, resourceType, resourceId, null);
    }

    // 멱등 발행용 팩터리. dedup_key(UNIQUE)로 같은 (수신자·유형·리소스)의 중복 저장을 DB가 원자적으로 막는다.
    public static Notification createIdempotent(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        return new Notification(
                memberId, type, content, resourceType, resourceId,
                dedupKey(memberId, type, resourceType, resourceId));
    }

    // 멱등 키 조합. 유형을 앞에 둬 유형별로 독립적인 1건 제약이 되게 한다(PAYMENT_PENDING 안내와 다른 유형이 섞이지 않음).
    public static String dedupKey(
            Long memberId,
            NotificationType type,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        return type + ":" + resourceType + ":" + resourceId + ":" + memberId;
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
