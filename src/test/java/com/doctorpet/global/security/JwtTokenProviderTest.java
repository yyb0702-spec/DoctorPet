package com.doctorpet.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Access Token과 Refresh Token은 만료 시간 외 클레임 구조가 같아서, tokenType 클레임으로
 * 용도를 구분하지 않으면 서로 바꿔 쓸 수 있었다(리뷰에서 지적된 P1). 이 클래스는 발급된 토큰이
 * 실제로 올바른 tokenType을 갖는지, 그리고 그 값으로 두 종류가 명확히 구분되는지를 검증한다.
 *
 * 만료 경계 회귀 테스트(리뷰 지적, #124)도 함께 둔다 — JwtAuthenticationFilter가
 * validateToken()으로 서명·만료를 확인한 직후와, 그 토큰이 getJti()/getRemainingTtl()로
 * 다시 파싱되는 시점 사이에 만료 경계를 넘으면, jjwt 기본 동작(parseSignedClaims가 만료 시
 * ExpiredJwtException을 던짐) 때문에 로그아웃이 500으로 끝나는 문제가 있었다.
 */
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-key-for-jwt-must-be-long-enough-0123456789");
        jwtProperties.setAccessTokenExpiration(3_600_000L);
        jwtProperties.setRefreshTokenExpiration(1_209_600_000L);

        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        jwtTokenProvider.init();
    }

    @Test
    @DisplayName("Access Token은 tokenType=ACCESS로 발급되고 검증을 통과한다")
    void generateAccessToken_hasAccessType() {
        String token = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getTokenType(token)).isEqualTo(TokenType.ACCESS);
    }

    @Test
    @DisplayName("Refresh Token은 tokenType=REFRESH로 발급되고 검증을 통과한다")
    void generateRefreshToken_hasRefreshType() {
        String token = jwtTokenProvider.generateRefreshToken(1L, "guardian@example.com", "GUARDIAN");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getTokenType(token)).isEqualTo(TokenType.REFRESH);
    }

    @Test
    @DisplayName("Access Token과 Refresh Token은 subject·email·role은 같지만 tokenType으로 명확히 구분된다")
    void accessAndRefreshTokens_shareClaimsButDifferByType() {
        String accessToken = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");
        String refreshToken = jwtTokenProvider.generateRefreshToken(1L, "guardian@example.com", "GUARDIAN");

        MemberPrincipal fromAccess = jwtTokenProvider.getMemberPrincipal(accessToken);
        MemberPrincipal fromRefresh = jwtTokenProvider.getMemberPrincipal(refreshToken);

        assertThat(fromAccess).isEqualTo(fromRefresh);
        assertThat(jwtTokenProvider.getTokenType(accessToken)).isNotEqualTo(jwtTokenProvider.getTokenType(refreshToken));
    }

    @Test
    @DisplayName("서명이 위조된 토큰은 검증에 실패한다")
    void validateToken_tamperedSignature_returnsFalse() {
        String tampered = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN") + "tampered";

        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("이미 만료된 토큰이어도 getJti는 예외 없이 jti를 반환한다(만료 경계 로그아웃 500 회귀 방지)")
    void getJti_expiredToken_returnsJtiWithoutThrowing() {
        jwtProperties.setAccessTokenExpiration(-1000L); // 발급 시점부터 이미 만료
        String expiredToken = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");

        assertThat(jwtTokenProvider.getJti(expiredToken)).isNotBlank();
    }

    @Test
    @DisplayName("이미 만료된 토큰이면 getRemainingTtl은 예외 없이 Duration.ZERO를 반환한다")
    void getRemainingTtl_expiredToken_returnsZeroWithoutThrowing() {
        jwtProperties.setAccessTokenExpiration(-1000L);
        String expiredToken = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");

        assertThat(jwtTokenProvider.getRemainingTtl(expiredToken)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("만료된 토큰에 대해 getTokenType은 여전히 예외를 던진다(인증 판단 경로는 관대해지면 안 됨)")
    void getTokenType_expiredToken_stillThrows() {
        jwtProperties.setAccessTokenExpiration(-1000L);
        String expiredToken = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");

        assertThatThrownBy(() -> jwtTokenProvider.getTokenType(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("만료된 토큰에 대해 validateToken은 여전히 false다")
    void validateToken_expiredToken_returnsFalse() {
        jwtProperties.setAccessTokenExpiration(-1000L);
        String expiredToken = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");

        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
    }
}
