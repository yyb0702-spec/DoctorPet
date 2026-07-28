package com.doctorpet.global.gateway.payment;

/**
 * 결제 게이트웨이 호출 실패. 재시도 성격({@link GatewayFailureReason})과
 * 공급자 원본 오류 코드를 함께 전달해 상위 서비스가 분기(재시도/오프라인 전환/조회)하도록 한다(SA §9-4).
 *
 * <p>도메인 {@code ServiceException}과 분리한다 — 게이트웨이는 HTTP 상태·코드 매핑까지만 알고,
 * 결제 상태 전이·사용자 응답은 상위 계층이 결정한다.
 */
public class PaymentGatewayException extends RuntimeException {

    private final GatewayFailureReason failureReason;

    /** 공급자 원본 오류 코드(로깅·매핑 추적용, 없으면 null). 민감정보는 담지 않는다. */
    private final String providerErrorCode;

    public PaymentGatewayException(GatewayFailureReason failureReason, String providerErrorCode, String message) {
        super(message);
        this.failureReason = failureReason;
        this.providerErrorCode = providerErrorCode;
    }

    public PaymentGatewayException(GatewayFailureReason failureReason, String providerErrorCode, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
        this.providerErrorCode = providerErrorCode;
    }

    public GatewayFailureReason getFailureReason() {
        return failureReason;
    }

    public String getProviderErrorCode() {
        return providerErrorCode;
    }
}
