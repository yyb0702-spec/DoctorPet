package com.doctorpet.domain.reservation.notification;

/** 예약 상태 변경을 보호자 알림으로 전달하는 도메인 포트. */
public interface ReservationNotificationPublisher {

    void publishRejected(Long guardianMemberId, Long reservationId);

    void publishNoShow(Long guardianMemberId, Long reservationId);
}
