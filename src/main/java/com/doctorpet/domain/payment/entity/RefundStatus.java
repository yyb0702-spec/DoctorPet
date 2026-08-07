package com.doctorpet.domain.payment.entity;

/*
  환불 이력 행의 처리 상태(#37). payments.status(REFUNDED)와 별개다 — 이쪽은 "PG 취소 요청이 어디까지 갔는지"를
  나타내고, payments.status는 확정된 결제 결과다.
    REQUESTED → COMPLETED   (PG 취소 성공 → payments PAID→REFUNDED 전이)
    REQUESTED → FAILED      (PG 취소 실패 → payments는 PAID 유지)
    FAILED    → REQUESTED   (같은 merchant_refund_id로 재시도 선점)
  FAILED에서 재시도가 가능해야 하므로 이 상태만 역방향 전이를 허용한다. 재시도가 같은 멱등키를 재사용하기에
  PG 이중 취소는 발생하지 않는다. COMPLETED는 종착 상태이며 재요청은 멱등 응답으로 흡수한다.
 */
public enum RefundStatus {
    REQUESTED,
    COMPLETED,
    FAILED
}
