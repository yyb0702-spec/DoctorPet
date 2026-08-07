package com.doctorpet.domain.payment.entity;

/*
  결제 상태(SA §5-2). 예약 상태와 분리해 관리한다 — 어느 한쪽에 다른 쪽 상태를 넣지 않는다(SA §5).
  전진 단선 흐름이라 행이 덮어써지지 않고 단일 행으로 이력이 보존된다:
    PENDING → PAID                     (빌링키 승인 성공)
    PENDING → OFFLINE_REQUIRED         (재시도 무의미/재시도 소진/결제수단 비활성)
    OFFLINE_REQUIRED → OFFLINE_PAID    (병원 오프라인 수납, #36)
    PAID → REFUNDED                    (오청구 전액 환불, #37)
  타임아웃 등 결과 미확정은 별도 상태 없이 PENDING에 머문다(정산 스케줄러 #35가 단건조회로 확정).

  REFUNDED는 빌링키 결제(PAID)만의 종착 상태다. 환불 진행 중을 나타내는 중간 상태는 두지 않는다 —
  선점(claim)은 payment_refunds의 UNIQUE(payment_id) INSERT가 담당하므로 결제 상태에 중간 단계를
  넣을 필요가 없고, 넣으면 실패 시 되돌리는 역방향 전이가 생겨 전진 단선 원칙이 깨진다(#37).
  현장 현금 수납(OFFLINE_PAID)의 환불은 반환 절차·증빙 정책이 미정이라 범위 밖이다(PRD §환불).
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    OFFLINE_REQUIRED,
    OFFLINE_PAID,
    REFUNDED
}
