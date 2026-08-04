package com.doctorpet.domain.payment.entity;

/*
  결제 상태(SA §5-2). 예약 상태와 분리해 관리한다 — 어느 한쪽에 다른 쪽 상태를 넣지 않는다(SA §5).
  단선 흐름이라 행이 덮어써지지 않고 단일 행으로 이력이 보존된다:
    PENDING → PAID                     (빌링키 승인 성공)
    PENDING → OFFLINE_REQUIRED         (재시도 무의미/재시도 소진/결제수단 비활성)
    OFFLINE_REQUIRED → OFFLINE_PAID    (병원 오프라인 수납, #36)
  타임아웃 등 결과 미확정은 별도 상태 없이 PENDING에 머문다(정산 스케줄러 #35가 단건조회로 확정).
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    OFFLINE_REQUIRED,
    OFFLINE_PAID
}
