package com.doctorpet.domain.notification.adapter;

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 예약 도메인의 알림 발행 요청을 notifications 테이블 저장으로 연결한다. */
@Component
@RequiredArgsConstructor
public class StoringReservationNotificationPublisher
        implements ReservationNotificationPublisher {

    private final NotificationService notificationService;

    @Override
    public void publishRejected(Long guardianMemberId, Long reservationId) {
        notificationService.create(
                guardianMemberId,
                NotificationType.RESERVATION_REJECTED,
                "병원의 승인 시간이 지나 예약 요청이 자동으로 거절되었습니다.",
                NotificationResourceType.RESERVATION,
                reservationId
        );
    }
}
