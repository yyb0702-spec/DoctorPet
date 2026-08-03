package com.doctorpet.domain.payment.service;

import com.doctorpet.global.gateway.payment.support.SensitiveDataMasker;

/**
 * 선기록(Tx1) 결과를 외부 호출(Tx 밖) 단계로 넘기기 위한 값 객체. 트랜잭션 경계를 넘기므로 엔티티가 아니라
 * 값으로 전달한다. billingKeyEnc(암호문)는 청구 승인에 필요해 담지만, 로그·예외에 새지 않도록 toString에서 마스킹한다.
 *
 * @param paymentId           선기록된 결제 레코드 id
 * @param merchantPaymentId   멱등키
 * @param billingKeyEnc       암호화된 빌링키(승인 직전 복호화). 원문·평문 저장·로그 금지
 * @param amount              청구 금액
 * @param paymentMethodActive 결제수단이 ACTIVE인지 — false면 게이트웨이 호출 없이 OFFLINE_REQUIRED 확정(SA §9-4)
 * @param guardianMemberId    예약 보호자(알림 수신자)
 * @param reservationId       예약 식별자(주문명·알림에 사용)
 */
public record PaymentPreRecord(
        Long paymentId,
        String merchantPaymentId,
        String billingKeyEnc,
        int amount,
        boolean paymentMethodActive,
        Long guardianMemberId,
        Long reservationId
) {

    @Override
    public String toString() {
        return "PaymentPreRecord[paymentId=%d, merchantPaymentId=%s, billingKeyEnc=%s, amount=%d, paymentMethodActive=%s, guardianMemberId=%d, reservationId=%d]"
                .formatted(paymentId, merchantPaymentId, SensitiveDataMasker.maskSecret(billingKeyEnc),
                        amount, paymentMethodActive, guardianMemberId, reservationId);
    }
}
