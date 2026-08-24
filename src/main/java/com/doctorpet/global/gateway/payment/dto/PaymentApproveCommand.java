package com.doctorpet.global.gateway.payment.dto;

import com.doctorpet.global.gateway.payment.support.SensitiveDataMasker;

/**
 * 빌링키 결제 승인 요청(도메인 중립). 상위 결제 서비스가 조립해 게이트웨이에 전달한다.
 *
 * @param merchantPaymentId 서버가 생성한 멱등키. 승인 요청·재시도·조회에 동일하게 사용한다(SA §9-4).
 * @param billingKey        승인에 사용할 빌링키(원본은 로깅·저장 금지)
 * @param amount            청구 금액(원). 금액 유효성(0 초과·상한)은 상위 서비스가 검증한다.
 * @param orderName         공급자 표기용 주문명(민감정보 포함 금지)
 */
public record PaymentApproveCommand(
        String merchantPaymentId,
        String billingKey,
        int amount,
        String orderName
) {

    /**
     * record 기본 toString은 billingKey 원본을 평문 노출하므로 마스킹해 재정의한다(보안 규칙, 이슈 #38).
     * 이 커맨드가 로그·예외 메시지에 통째로 실려도 빌링키가 새지 않도록 방어한다.
     */
    @Override
    public String toString() {
        return "PaymentApproveCommand[merchantPaymentId=%s, billingKey=%s, amount=%d, orderName=%s]"
                .formatted(merchantPaymentId, SensitiveDataMasker.maskSecret(billingKey), amount, orderName);
    }
}
