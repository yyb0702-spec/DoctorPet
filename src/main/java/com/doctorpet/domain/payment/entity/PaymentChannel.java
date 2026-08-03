package com.doctorpet.domain.payment.entity;

/*
  결제 채널(SA §5-2). 자동 결제 성공은 BILLING_KEY, 병원 오프라인 수납은 OFFLINE.
  청구 준비(PENDING)·오프라인 필요(OFFLINE_REQUIRED) 단계에서는 정산 방식이 미확정이라 null이며,
  결과가 확정될 때(PAID→BILLING_KEY, OFFLINE_PAID→OFFLINE) 채워진다.
 */
public enum PaymentChannel {
    BILLING_KEY,
    OFFLINE
}
