package com.doctorpet.domain.notification.entity.status;

// 알림 유형(SA §4 notifications.type). MVP(#39)에서 실제 발행은 결제 결과(PAYMENT_RESULT)만이며,
// 예약·노쇼 유형은 후속 이벤트 연동 시 발행된다(임의 확장 금지).
// PAYMENT_PENDING은 결제 고도화 3.6에서 추가한다 — 정산이 오래 미확정(RECONCILE_STUCK)일 때 발행하는
// "결제 확인 중" 안내의 실재하는 유형이다(전체 삭제 뒤에도 결제당 1회). 이 1회는 표시 행과 분리된
// notification_delivery_marks.dedup_key UNIQUE 제약이 원자적으로 보장한다(동시 발행 경합에서도 1건).
// 컬럼은 VARCHAR이고 CHECK 제약도 없다(이슈 #176) — 값이 늘어도 마이그레이션 없이 상수만 추가하면 되고, 대신
// DB가 값을 검증하지 않으므로 유효성은 이 enum 파싱이 전담한다. CHECK를 함께 없애야 하는 이유는 Hibernate가
// VARCHAR enum 컬럼에 허용 값을 열거하는 CHECK 제약을 자동 생성하기 때문이다(신규 DB에서만 생성되고
// @Column(columnDefinition)으로도 억제되지 않는다) — 남겨두면 ENUM과 똑같이 값 추가마다 DDL이 필요해진다.
// NotificationTypeVarcharMigrationRunner가 그 제약을 드롭한다. 다만 varchar 길이(엔티티 length = 40)를 넘는
// 이름은 저장이 실패하므로 40자를 넘기지 않는다 — NotificationEnumColumnSchemaIntegrationTest가 실제 컬럼으로 검사한다.
public enum NotificationType {
    // 병원 수신(HOSPITAL) 유형. 보호자가 새 예약을 요청했을 때 해당 병원에 발행한다(#166).
    RESERVATION_REQUESTED,
    RESERVATION_CONFIRMED,
    RESERVATION_REJECTED,
    RESERVATION_HOSPITAL_CANCELED,
    RESERVATION_WAITLIST_OFFERED,
    PAYMENT_RESULT,
    PAYMENT_PENDING,
    NO_SHOW
}
