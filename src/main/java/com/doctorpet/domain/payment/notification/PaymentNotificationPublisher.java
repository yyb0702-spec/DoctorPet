package com.doctorpet.domain.payment.notification;

import com.doctorpet.domain.payment.entity.PaymentStatus;

/**
 * 결제 상태 전이 알림 발행 계약(SA §9-8, 이슈 #34·#39). 결제 결과(PAID·OFFLINE_REQUIRED 등)를 보호자에게
 * 알리기 위한 이벤트를 발행한다. MVP 알림은 폴링이며 push는 인터페이스로만 추상화한다(SA §9-8, 부록 A 미확정).
 *
 * <p>#34 범위에서는 <b>발행 계약만</b> 둔다. notifications 테이블·엔티티(#39)가 아직 없어 실제 저장은 하지 않고
 * 기본 구현({@link LoggingPaymentNotificationPublisher})은 로그만 남긴다. #39가 저장 구현을 제공하면
 * 결제 흐름 코드 변경 없이 그 빈이 대체한다.
 */
public interface PaymentNotificationPublisher {

    /**
     * 진료비 청구 결과를 발행한다. 결제 흐름의 트랜잭션 커밋 이후에 호출한다(발행 실패가 결제 확정을 되돌리지 않도록).
     *
     * @param guardianMemberId 알림 수신자(예약 보호자)
     * @param reservationId    관련 예약
     * @param paymentId        결제 레코드
     * @param status           확정된 결제 상태
     */
    void publishChargeResult(Long guardianMemberId, Long reservationId, Long paymentId, PaymentStatus status);
}
