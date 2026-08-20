package com.doctorpet.domain.notification.entity.status;

// 알림이 가리키는 연결 리소스의 종류(generic 참조). resource_id와 함께 저장돼 알림→원본 리소스 이동에 쓰인다.
// 유형이 늘어도 스키마 변경 없이 값만 추가하면 된다 — 컬럼이 VARCHAR라서 성립하는 말이다(이슈 #176).
// ENUM이던 동안에는 사실이 아니었다(기존 DB에서 새 값의 INSERT만 실패했다). 길이 한도(엔티티 length = 40)만
// 지키면 되고, NotificationEnumColumnSchemaIntegrationTest가 실제 컬럼으로 이 전제를 검사한다.
public enum NotificationResourceType {
    RESERVATION,
    RESERVATION_WAITLIST,
    PAYMENT
}
