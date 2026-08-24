package com.doctorpet.domain.notification.entity;

// 표시용 notifications 행과 분리해 멱등 발행 이력을 보존한다. 알림 전체 삭제는 notifications만 지우므로,
// PAYMENT_PENDING 안내를 삭제한 뒤 정산 배치가 같은 결제에 다시 안내를 생성하지 못하게 한다(PR #213 리뷰).

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "notification_delivery_marks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_delivery_marks_dedup_key",
                columnNames = "dedup_key")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDeliveryMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    // 다른 도메인 엔티티와 같은 applicationClock을 쓰도록 JPA auditing으로 기록한다. 시스템 기본 시간대를 직접
    // 읽으면 알림 행의 BaseEntity 감사 시각과 기준이 달라질 수 있다.
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private NotificationDeliveryMark(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public static NotificationDeliveryMark of(String dedupKey) {
        return new NotificationDeliveryMark(dedupKey);
    }
}
