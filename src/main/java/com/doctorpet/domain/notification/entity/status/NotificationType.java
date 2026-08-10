package com.doctorpet.domain.notification.entity.status;

// 알림 유형(SA §4 notifications.type). MVP(#39)에서 실제 발행은 결제 결과(PAYMENT_RESULT)만이며,
// 예약·노쇼 유형은 후속 이벤트 연동 시 발행된다(임의 확장 금지).
// PAYMENT_PENDING은 결제 고도화 3.6에서 추가한다 — 정산이 오래 미확정(RECONCILE_STUCK)일 때 발행하는
// "결제 확인 중" 안내의 실재하는 유형이며, 이 유형+resourceId(paymentId) 존재 여부를 STUCK 안내의 멱등 마커로
// 쓴다(결제당 1회). type이 EnumType.STRING(varchar)이라 값 추가에 스키마 마이그레이션이 필요 없다.
public enum NotificationType {
    RESERVATION_CONFIRMED,
    RESERVATION_REJECTED,
    PAYMENT_RESULT,
    PAYMENT_PENDING,
    NO_SHOW
}
