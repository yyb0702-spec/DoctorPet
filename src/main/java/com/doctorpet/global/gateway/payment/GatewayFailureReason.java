package com.doctorpet.global.gateway.payment;

/**
 * 결제 실패의 재시도 성격 분류. 게이트웨이는 공급자 오류를 이 값으로 "분류만" 하고,
 * 실제 재시도 루프·상태 전이(OFFLINE_REQUIRED 확정)는 상위 결제 서비스가 수행한다(SA §9-4).
 */
public enum GatewayFailureReason {

    /**
     * 재시도 유효 — 네트워크 오류·일시 장애·5xx 등.
     * 상위 서비스는 단건 조회로 미처리 확인 후 제한 횟수 재시도한다.
     */
    RETRIABLE,

    /**
     * 재시도 무의미 — 한도 초과·카드 정지·빌링키 만료·삭제된 결제수단 등.
     * 상위 서비스는 재시도 없이 즉시 OFFLINE_REQUIRED로 확정한다.
     */
    NON_RETRIABLE,

    /**
     * 결과 미확정 — 타임아웃·응답 유실로 승인 여부를 알 수 없음.
     * 상위 서비스는 무조건 재시도하지 않고 먼저 단건 조회로 확인한다(이미 승인됐을 수 있음).
     */
    UNKNOWN
}
