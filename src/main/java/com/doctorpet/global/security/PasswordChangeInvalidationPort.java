package com.doctorpet.global.security;

import java.util.Date;

/**
 * 비밀번호 재설정으로 무효화된 Access Token을 인증 단계에서 걸러내기 위한 포트(기능 구멍 점검
 * 대응 — 재설정 뒤에도 이미 발급된 Access Token이 만료 전까지 계속 유효했던 문제).
 *
 * {@link MemberBlacklistPort}(탈퇴)와 다른 이유로 별도 포트를 둔다 — 탈퇴는 그 회원으로 다시
 * 로그인할 방법 자체가 없어(이메일 익명화) memberId 단위로 영구히(TTL만큼) 막아도 안전하지만,
 * 비밀번호 재설정은 사용자가 새 비밀번호로 바로 다시 로그인해 같은 memberId로 새 Access Token을
 * 받는 것이 정상 흐름이다. memberId만 보고 막으면 그 새 토큰까지 최대 Access Token TTL만큼
 * 막혀버린다 — 그래서 "이 토큰이 재설정 시각 이전에 발급됐는가"(iat 비교)까지 함께 확인해,
 * 재설정 이전 토큰(공격자가 탈취했을 수 있는 옛 세션 포함)만 걸러내고 재설정 이후 새로 발급된
 * 토큰은 영향받지 않게 한다.
 *
 * 실제 저장소(Redis 등)는 domain.member 쪽 구현(RefreshTokenRepository)에 있다 — global
 * 패키지가 domain을 직접 참조하면 안 되므로, JwtAuthenticationFilter는 이 인터페이스에만
 * 의존하고 실제 구현은 Spring이 주입한다.
 */
public interface PasswordChangeInvalidationPort {

    /**
     * tokenIssuedAt이 이 회원의 마지막 비밀번호 재설정 시각보다 이전이면(즉 재설정 전에 발급된
     * 토큰이면) true를 반환한다. 재설정 이력이 없거나(TTL 만료 포함) tokenIssuedAt이 재설정
     * 이후면 false다.
     */
    boolean isTokenInvalidatedByPasswordChange(Long memberId, Date tokenIssuedAt);
}
