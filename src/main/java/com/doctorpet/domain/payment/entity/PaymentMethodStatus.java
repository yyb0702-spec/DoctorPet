package com.doctorpet.domain.payment.entity;

/*
  결제수단 상태(SA §4 payment_methods). #34 청구 시점에 ACTIVE 여부를 재확인하고,
  ACTIVE가 아니면 자동 청구 없이 OFFLINE_REQUIRED로 확정한다(SA §9-4·§4-2).
  삭제는 물리 삭제 대신 DELETED 전이(소프트 삭제)로 처리해 payments의 FK·이력을 보존한다.
 */
public enum PaymentMethodStatus {
    ACTIVE,
    EXPIRED,
    DELETED
}
