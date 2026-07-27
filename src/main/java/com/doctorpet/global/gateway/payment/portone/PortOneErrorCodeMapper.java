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

    /**
     * 공급자 오류 코드를 재시도 성격으로 분류한다. null·미인식 코드는 {@code UNKNOWN}.
     */
    public GatewayFailureReason classify(String providerErrorCode) {
        if (providerErrorCode == null || providerErrorCode.isBlank()) {
            return GatewayFailureReason.UNKNOWN;
        }
        String code = providerErrorCode.trim().toUpperCase();
        if (NON_RETRIABLE_CODES.contains(code)) {
            return GatewayFailureReason.NON_RETRIABLE;
        }
        if (RETRIABLE_CODES.contains(code)) {
            return GatewayFailureReason.RETRIABLE;
        }
        return GatewayFailureReason.UNKNOWN;
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
