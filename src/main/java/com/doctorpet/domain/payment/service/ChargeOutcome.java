package com.doctorpet.domain.payment.service;

import java.time.LocalDateTime;

/**
 * 외부 승인(Tx 밖) 단계가 판정한 청구 결과. 후확정(Tx2)이 이 값으로 결제 상태를 전이한다.
 * 게이트웨이 승인 실패는 예외가 아니라 이 값의 OFFLINE_REQUIRED/PENDING으로 표현된다(SA §9-4 — 실패해도 청구가
 * 500으로 새지 않고 오프라인 수납·정산 스케줄러로 이어진다).
 */
public record ChargeOutcome(
        Type type,
        String pgPaymentId,
        LocalDateTime paidAt,
        String failureReason,
        int retryCount
) {

    public enum Type {
        PAID,
        OFFLINE_REQUIRED,
        PENDING
    }

    /** 빌링키 승인 성공. */
    public static ChargeOutcome paid(String pgPaymentId, LocalDateTime paidAt) {
        return new ChargeOutcome(Type.PAID, pgPaymentId, paidAt, null, 0);
    }

    /** 재시도 무의미/소진/결제수단 비활성 → 오프라인 수납 대상 확정. */
    public static ChargeOutcome offlineRequired(String failureReason, int retryCount) {
        return new ChargeOutcome(Type.OFFLINE_REQUIRED, null, null, failureReason, retryCount);
    }

    /** 결과 미확정(타임아웃) → PENDING 유지, 정산 스케줄러(#35)가 단건조회로 확정. */
    public static ChargeOutcome pending(int retryCount, String failureReason) {
        return new ChargeOutcome(Type.PENDING, null, null, failureReason, retryCount);
    }
}
