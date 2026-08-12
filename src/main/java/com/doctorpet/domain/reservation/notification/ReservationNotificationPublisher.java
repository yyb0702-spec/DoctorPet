package com.doctorpet.domain.reservation.notification;

/**
 * 예약 상태 변경을 보호자 알림으로 전달하는 도메인 포트(SA §9-8, 이슈 #88).
 *
 * <p>대상 이벤트는 승인(CONFIRMED)·거절(REJECTED: 병원 수동/승인 타임아웃 자동)·노쇼(NO_SHOW)다. 저장은
 * 상태 전이 트랜잭션 안에서 이뤄져 전이가 롤백되면 알림도 남지 않는다 — 이 계약이 이 포트의 범위다.
 *
 * <p>실시간 전송(push)은 이 포트의 책임이 아니다. 이 이슈(#88) 범위에서 보호자는 폴링으로 알림을 받으며,
 * push 채널은 별도로 도입된다(#40/PR #106). 도입되면 저장 커밋 이후에만 실행되도록 연결되므로 예약
 * 트랜잭션에 영향을 주지 않는다 — 알림 저장이 원본이고 push는 부가 채널이다.
 */
public interface ReservationNotificationPublisher {

    /** 병원이 예약 요청을 승인했다(REQUESTED → CONFIRMED). */
    void publishConfirmed(Long guardianMemberId, Long reservationId);

    /** 병원 스태프가 예약 요청을 수동으로 거절했다(REQUESTED → REJECTED). */
    void publishRejected(
            Long guardianMemberId,
            Long reservationId,
            String rejectReason
    );

    /** 병원이 확정 예약을 취소했다(CONFIRMED → HOSPITAL_CANCELED). */
    void publishHospitalCanceled(
            Long guardianMemberId,
            Long reservationId,
            String reason
    );

    /** 승인 데드라인 경과로 스케줄러가 예약 요청을 자동 거절했다(REQUESTED → REJECTED, TIMEOUT_REJECTED). */
    void publishAutoRejected(Long guardianMemberId, Long reservationId);

    /** 예약이 노쇼로 확정됐다(CONFIRMED → NO_SHOW). */
    void publishNoShow(Long guardianMemberId, Long reservationId);
}
