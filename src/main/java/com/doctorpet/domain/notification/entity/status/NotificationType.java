package com.doctorpet.domain.notification.entity.status;

// 알림 유형(SA §4 notifications.type). MVP(#39)에서 실제 발행은 결제 결과(PAYMENT_RESULT)만이며,
// 예약·노쇼 유형은 후속 이벤트 연동 시 발행된다(임의 확장 금지).
public enum NotificationType {
    RESERVATION_CONFIRMED,
    RESERVATION_REJECTED,
    RESERVATION_HOSPITAL_CANCELLED,
    PAYMENT_RESULT,
    NO_SHOW
}
