package com.doctorpet.global.security;

/**
 * 로그아웃된 특정 Access Token을 인증 단계에서 걸러내기 위한 포트다. {@link MemberBlacklistPort}와
 * 의도적으로 분리했다 — 그쪽은 "이 회원의 모든 토큰을 막는다"(탈퇴처럼 재로그인 자체가 막히는
 * 상황)이고, 이쪽은 "이 토큰 한 장만 막는다"(로그아웃 후에도 재로그인은 정상적으로 가능해야
 * 하므로, 회원 단위로 막으면 재로그인으로 새로 받은 토큰까지 함께 막혀버린다). 실제 저장소는
 * domain.member 쪽 구현(RefreshTokenRepository)에 있다 — global이 domain을 직접 참조하면 안
 * 되므로 JwtAuthenticationFilter는 이 인터페이스에만 의존한다.
 */
public interface AccessTokenBlacklistPort {

    boolean isBlacklisted(String jti);
}
