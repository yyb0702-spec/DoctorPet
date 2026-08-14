package com.doctorpet.domain.notification.entity.status;

// 알림이 가리키는 연결 리소스의 종류(generic 참조). resource_id와 함께 저장돼 알림→원본 리소스 이동에 쓰인다.
// 유형이 늘어도 스키마 변경 없이 값만 추가하면 된다.
public enum NotificationResourceType {
    RESERVATION,
    RESERVATION_WAITLIST,
    PAYMENT
}
