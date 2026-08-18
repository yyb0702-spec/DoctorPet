package com.doctorpet.domain.member.exception;

/**
 * 인증메일 rate limit 카운터(Redis) 접근이 실패했을 때 던진다(기능 구멍 점검 대응) —
 * domain.ai.exception.AiRateLimitStorageException과 같은 이유로 별도 타입을 둔다. 이 예외만
 * AuthRateLimiter가 fail-open(허용) 대상으로 잡는다 — 그 외 예상하지 못한 런타임 예외까지
 * 통째로 삼키면 진짜 프로그래밍 버그도 "허용"으로 조용히 넘어가 버리므로, 저장소 계층에서
 * 명확히 구분해 표시한다.
 */
public class AuthRateLimitStorageException extends RuntimeException {

    public AuthRateLimitStorageException(String message) {
        super(message);
    }

    public AuthRateLimitStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
