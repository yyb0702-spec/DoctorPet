package com.doctorpet.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Access Token과 Refresh Token은 만료 시간 외 클레임 구조가 같아서, tokenType 클레임으로
 * 용도를 구분하지 않으면 서로 바꿔 쓸 수 있었다(리뷰에서 지적된 P1). 이 클래스는 발급된 토큰이
 * 실제로 올바른 tokenType을 갖는지, 그리고 그 값으로 두 종류가 명확히 구분되는지를 검증한다.
 */
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
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
}
