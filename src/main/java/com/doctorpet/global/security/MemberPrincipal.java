package com.doctorpet.global.security;

/*
  인증된 사용자 정보. JWT 검증 후 SecurityContext의 Authentication.principal로 저장되며,
  Controller에서는 @AuthenticationPrincipal MemberPrincipal principal 로 주입받는다.
  요청 body/query/path의 memberId·hospitalId는 신뢰하지 않고, 반드시 이 principal에서 얻는다.
 */
public record MemberPrincipal(
        Long memberId,
        String email,
        String role
) {
}
