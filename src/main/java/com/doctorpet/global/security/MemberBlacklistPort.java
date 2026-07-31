package com.doctorpet.global.security;

/**
 * 탈퇴 등으로 무효화된 회원의 Access Token을 인증 단계에서 걸러내기 위한 포트다. 실제 저장소
 * (Redis 등)는 domain.member 쪽 구현(RefreshTokenRepository)에 있다 — global 패키지가 domain을
 * 직접 참조하면 안 되므로(AuthService의 "global이 domain을 참조하면 안 된다" 주석과 같은 이유),
 * JwtAuthenticationFilter는 이 인터페이스에만 의존하고 실제 구현은 Spring이 주입한다.
 */
public interface MemberBlacklistPort {

    boolean isBlacklisted(Long memberId);
}
