package com.doctorpet.domain.notification.adapter;

// 예약 도메인의 알림 발행 요청을 notifications 테이블 저장으로 연결한다(#88).
// 저장은 호출자의 상태 전이 트랜잭션에 참여하므로, 전이가 롤백되면 알림도 남지 않는다.
// (실시간 전송은 이 클래스의 책임이 아니다 — 별도로 도입되는 push 채널이 저장된 알림을 전달한다.)

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
    public void publishRejected(
            Long guardianMemberId,
            Long reservationId,
            String rejectReason
    ) {
        notificationService.create(
                guardianMemberId,
                NotificationType.RESERVATION_REJECTED,
                "병원이 예약 요청을 거절했습니다. 사유: " + rejectReason,
                NotificationResourceType.RESERVATION,
                reservationId
        );
    }

    @Override
    public void publishHospitalCancelled(
            Long guardianMemberId,
            Long reservationId,
            String reason
    ) {
        notificationService.create(
                guardianMemberId,
                NotificationType.RESERVATION_HOSPITAL_CANCELED,
                "병원이 확정된 예약을 취소했습니다. 사유: " + reason,
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
