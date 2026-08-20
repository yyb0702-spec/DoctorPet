package com.doctorpet.domain.notification.entity;

// 회원별 알림 수신 설정(고도화 3.9). (회원 · 알림 유형 · 채널)당 1행이고 값은 on/off 하나다.
// 이 PR의 범위는 스키마 선반영과 이메일 채널이 그것을 존중하는 것까지다 — 설정을 바꾸는 API·화면은 없다.
// 행이 없으면 "수신"이 기본이므로, 설정을 소급 생성하지 않아도 기존 회원의 동작이 바뀌지 않는다
// (스키마를 먼저 두기로 한 이유 — 나중에 추가하면 기존 회원에게 소급 적용할 근거가 없다).
// 채널은 REALTIME·EMAIL만이다. 인앱 저장은 알림의 원본이라 끌 수 있는 대상이 아니다(NotificationChannelType).

import com.doctorpet.domain.notification.channel.NotificationChannelType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "notification_preferences",
        // (회원 · 유형 · 채널)당 1행. 설정 API가 생기면 이 제약이 upsert의 기준이 되고, 같은 조합이 둘로 갈려
        // 어느 쪽이 이기는지 모호해지는 상황을 DB가 막는다.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_preferences_member_type_channel",
                columnNames = {"member_id", "notification_type", "channel"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 회원 논리 참조. 병원 수신 알림은 병원 단위 공유라 개인 설정 대상이 아니다(고도화 3.10).
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // enum 컬럼은 @JdbcTypeCode(SqlTypes.VARCHAR)로 못박는다(이슈 #176) — 안 하면 Hibernate가 native ENUM으로
    // 만들고, 유형·채널을 추가할 때마다 ENUM 확장 마이그레이션이 필요해진다. 길이도 notifications.type과 맞춘다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "notification_type", nullable = false, length = 40)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannelType channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    private NotificationPreference(
            Long memberId,
            NotificationType notificationType,
            NotificationChannelType channel,
            boolean enabled
    ) {
        this.memberId = memberId;
        this.notificationType = notificationType;
        this.channel = channel;
        this.enabled = enabled;
    }

    public static NotificationPreference of(
            Long memberId,
            NotificationType notificationType,
            NotificationChannelType channel,
            boolean enabled
    ) {
        return new NotificationPreference(memberId, notificationType, channel, enabled);
    }

    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
