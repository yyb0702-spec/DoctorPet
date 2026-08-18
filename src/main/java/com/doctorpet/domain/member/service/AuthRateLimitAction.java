package com.doctorpet.domain.member.service;

/**
 * AuthRateLimiter가 구분해서 세는 행동 종류(기능 구멍 점검 대응). 세 엔드포인트 모두 비인증
 * 상태에서 임의의 이메일로 메일을 발송시킬 수 있다는 공통점이 있지만, 카운터는 각자 독립적으로
 * 센다 — 한 엔드포인트를 한도까지 쓴 클라이언트가 다른 엔드포인트까지 덩달아 막히면 안 된다.
 */
public enum AuthRateLimitAction {
    SIGNUP,
    VERIFY_EMAIL_RESEND,
    PASSWORD_RESET_REQUEST
}
