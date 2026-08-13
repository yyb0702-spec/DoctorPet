package com.doctorpet.domain.notification.entity.status;

// 알림 유형(SA §4 notifications.type). MVP(#39)에서 실제 발행은 결제 결과(PAYMENT_RESULT)만이며,
// 예약·노쇼 유형은 후속 이벤트 연동 시 발행된다(임의 확장 금지).
// PAYMENT_PENDING은 결제 고도화 3.6에서 추가한다 — 정산이 오래 미확정(RECONCILE_STUCK)일 때 발행하는
// "결제 확인 중" 안내의 실재하는 유형이다(결제당 1회). 결제당 1회는 존재조회가 아니라 notifications.dedup_key
// UNIQUE 제약이 원자적으로 보장한다(동시 발행 경합에서도 1건). 엔티티는 EnumType.STRING이지만 운영 MySQL의
// notifications.type은 ENUM이므로 새 값은 NotificationWaitlistOfferedTypeMigrationRunner처럼 명시적으로 확장한다.
public enum NotificationType {
    RESERVATION_CONFIRMED,
    RESERVATION_REJECTED,
    RESERVATION_HOSPITAL_CANCELED,
    RESERVATION_WAITLIST_OFFERED,
    PAYMENT_RESULT,
    PAYMENT_PENDING,
    NO_SHOW
}
