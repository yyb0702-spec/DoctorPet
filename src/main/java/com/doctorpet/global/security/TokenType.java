package com.doctorpet.global.security;

/*
  JWT의 용도 구분. Access Token과 Refresh Token은 만료 시간 외에는 클레임 구조가 같아서
  서명·만료만 검증하면 서로 바꿔 쓸 수 있다 — 그래서 토큰 자체에 용도를 명시하고,
  사용처(인증 필터는 ACCESS만, 재발급은 REFRESH만)에서 반드시 이 값을 확인한다.
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
