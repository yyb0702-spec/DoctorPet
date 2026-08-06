package com.doctorpet.global.gateway.payment.portone;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PortOne 오류 코드·HTTP 상태를 내부 실패 사유({@link GatewayFailureReason})로 매핑한다(SA §9-4, 이슈 #38).
 *
 * <p>재시도 성격만 결정하고 상태 전이는 상위 서비스가 한다. 인식하지 못한 코드는 안전하게 {@code UNKNOWN}으로
 * 분류해 무분별한 재시도를 막는다(승인됐을 수 있으므로 단건 조회 우선).
 *
 * <p>여기 나열한 코드 문자열은 대표값이며, PortOne V2 실제 오류 코드 표는 실연동 시점에 확정·보강한다
 * (미확정 B-1 — 확정 전에는 미인식 코드가 {@code UNKNOWN}으로 안전 분류된다).
 */
@Component
public class PortOneErrorCodeMapper {

    /** 재시도 무의미 — 한도 초과·카드 정지·빌링키 만료·삭제 등. 즉시 OFFLINE_REQUIRED 대상. */
    private static final Set<String> NON_RETRIABLE_CODES = Set.of(
            "CARD_LIMIT_EXCEEDED",
            "EXCEEDS_MAX_AMOUNT",
            "CARD_SUSPENDED",
            "STOLEN_OR_LOST_CARD",
            "BILLING_KEY_NOT_FOUND",
            "BILLING_KEY_EXPIRED",
            "INVALID_CARD",
            "REJECTED_BY_ISSUER"
    );

    /** 재시도 유효 — PG 일시 장애·타임아웃 등. 단건 조회 후 제한 재시도 대상. */
    private static final Set<String> RETRIABLE_CODES = Set.of(
            "PG_TIMEOUT",
            "PG_PROVIDER_ERROR",
            "PG_TEMPORARILY_UNAVAILABLE",
            "TRANSACTION_TIMEOUT"
    );

    /*
      "이미 취소됨" 계열 코드(#37). 취소 재요청에서만 의미가 있고 실패가 아니다 — 같은 멱등키로 재시도했을 때
      PortOne이 내려주는 응답이므로, 실패로 올리면 상위의 재시도·복구 경로가 영구히 실패한다.
      게이트웨이가 단건조회로 취소를 확인한 뒤 성공(기존 취소 결과)으로 흡수하는 분기에 쓴다.
     */
    private static final Set<String> ALREADY_CANCELLED_CODES = Set.of(
            "PAYMENT_ALREADY_CANCELLED",
            "ALREADY_CANCELLED",
            "CANCELLATION_ALREADY_EXISTS"
    );

    /*
      취소 불가 — 재시도해도 결과가 같다. 취소 가능 금액 초과·이미 정산 완료된 결제 등.
      승인 경로의 NON_RETRIABLE과 성격이 같아 같은 분류로 보낸다(상위가 환불 실패로 기록하고 재시도하지 않는다).
     */
    private static final Set<String> NON_RETRIABLE_CANCEL_CODES = Set.of(
            "PAYMENT_NOT_PAID",
            "CANCEL_AMOUNT_EXCEEDS_CANCELLABLE_AMOUNT",
            "CANCELLABLE_AMOUNT_CONSUMED",
            "SETTLEMENT_ALREADY_COMPLETED"
    );

    /**
     * 공급자 오류 코드를 재시도 성격으로 분류한다. null·미인식 코드는 {@code UNKNOWN}.
     */
    public GatewayFailureReason classify(String providerErrorCode) {
        if (providerErrorCode == null || providerErrorCode.isBlank()) {
            return GatewayFailureReason.UNKNOWN;
        }
        String code = providerErrorCode.trim().toUpperCase();
        if (NON_RETRIABLE_CODES.contains(code) || NON_RETRIABLE_CANCEL_CODES.contains(code)) {
            return GatewayFailureReason.NON_RETRIABLE;
        }
        if (RETRIABLE_CODES.contains(code)) {
            return GatewayFailureReason.RETRIABLE;
        }
        return GatewayFailureReason.UNKNOWN;
    }

    /**
     * "이미 취소됨" 응답인지 판별한다(#37). 취소 재요청에서만 나타나며 실패가 아니다 — 호출부(게이트웨이)는
     * 단건조회로 취소를 확인한 뒤 기존 취소 결과를 성공으로 반환한다. 미인식 코드는 false이므로,
     * 실제 실패를 성공으로 오판하지 않는다(안전한 기본값).
     */
    public boolean isAlreadyCancelled(String providerErrorCode) {
        if (providerErrorCode == null || providerErrorCode.isBlank()) {
            return false;
        }
        return ALREADY_CANCELLED_CODES.contains(providerErrorCode.trim().toUpperCase());
    }

    /**
     * HTTP 상태 기반 분류(응답 본문 코드가 없을 때 보조). 5xx·408·429는 재시도 유효, 그 외 4xx는 미확정.
     */
    public GatewayFailureReason classifyHttpStatus(int httpStatus) {
        if (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429) {
            return GatewayFailureReason.RETRIABLE;
        }
        return GatewayFailureReason.UNKNOWN;
    }
}
