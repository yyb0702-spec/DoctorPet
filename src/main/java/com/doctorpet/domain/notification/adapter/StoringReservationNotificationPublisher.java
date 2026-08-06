package com.doctorpet.domain.notification.adapter;

// 예약 도메인의 알림 발행 요청을 notifications 테이블 저장으로 연결한다(#88). 저장은 상태 전이 트랜잭션에
// 참여하고, 실시간 전송(SSE)은 NotificationService가 커밋 이후에 트리거한다(SA §9-8).

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StoringReservationNotificationPublisher
        implements ReservationNotificationPublisher {

    private final NotificationService notificationService;

    @Override
    public void publishConfirmed(Long guardianMemberId, Long reservationId) {
        notificationService.create(
                guardianMemberId,
                NotificationType.RESERVATION_CONFIRMED,
                "병원이 예약 요청을 승인했습니다.",
                NotificationResourceType.RESERVATION,
                reservationId
        );
    }

    @Override
    public void publishRejected(Long guardianMemberId, Long reservationId) {
        notificationService.create(
                guardianMemberId,
                NotificationType.RESERVATION_REJECTED,
                "병원이 예약 요청을 거절했습니다.",
                NotificationResourceType.RESERVATION,
                reservationId
        );
    }

    @Override
    public void publishAutoRejected(Long guardianMemberId, Long reservationId) {
        notificationService.create(
                guardianMemberId,
                NotificationType.RESERVATION_REJECTED,
                "병원의 승인 시간이 지나 예약 요청이 자동으로 거절되었습니다.",
                NotificationResourceType.RESERVATION,
                reservationId
        );
    }

    // 자동 노쇼(#102)·수동 노쇼(#88) 모두 이 메서드를 공용한다. 통합 시 충돌을 줄이려 시그니처·문구를 #102에 맞춘다.
    @Override
    public void publishNoShow(Long guardianMemberId, Long reservationId) {
        notificationService.create(
                guardianMemberId,
                NotificationType.NO_SHOW,
                "예약 시간 이후 체크인이 확인되지 않아 노쇼로 처리되었습니다.",
                NotificationResourceType.RESERVATION,
                reservationId
        );
    }
}
