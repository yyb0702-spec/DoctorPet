package com.doctorpet.domain.notification.repository;

// 알림 수신 설정 조회(고도화 3.9). 설정 변경 API는 이 PR 범위가 아니므로 조회 계약만 둔다.

import com.doctorpet.domain.notification.channel.NotificationChannelType;
import com.doctorpet.domain.notification.entity.NotificationPreference;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    // 행이 없으면 "수신"이 기본이다(호출부가 Optional.orElse(true)로 해석) — 설정을 만들지 않은 기존 회원의
    // 동작이 바뀌지 않게 하기 위한 계약이다. uk_notification_preferences_member_type_channel이 1건을 보장한다.
    Optional<NotificationPreference> findByMemberIdAndNotificationTypeAndChannel(
            Long memberId,
            NotificationType notificationType,
            NotificationChannelType channel
    );
}
