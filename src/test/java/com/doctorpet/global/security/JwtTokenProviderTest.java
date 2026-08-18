package com.doctorpet.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
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
 *
 * jti 클레임이 없는(구버전) 토큰에 대한 getJti() 대체 식별자 회귀 테스트(3차 리뷰 지적)도
 * 함께 둔다 — 이 프로젝트의 jti 도입 자체가 최초 배포보다 훨씬 앞서 있어(git 이력상
 * b3aabbd) 실제로 운영에 jti 없는 Access Token이 존재할 가능성은 거의 없지만, null을
 * 그대로 블랙리스트 키에 이어붙이면 jti 없는 모든 토큰이 "at-blacklist:null"이라는 같은
 * 키로 충돌하는 위험은 코드만 보면 실재하므로 방어적으로 처리하고 그 동작을 고정한다.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-must-be-long-enough-0123456789";

    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        jwtProperties.setAccessTokenExpiration(3_600_000L);
        jwtProperties.setRefreshTokenExpiration(1_209_600_000L);

        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        jwtTokenProvider.init();
    }

    /** jti 클레임을 아예 넣지 않은 토큰을 만든다 — jti 도입 이전 구버전 토큰 상황을 재현한다. */
    private String buildTokenWithoutJti(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .claim("email", "guardian@example.com")
                .claim("role", "GUARDIAN")
                .claim("tokenType", "ACCESS")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000L))
                .signWith(key)
                .compact();
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

    @Test
    @DisplayName("jti 클레임이 없는 토큰이면 getJti는 원문 토큰의 SHA-256 해시로 대체한다(3차 리뷰 지적)")
    void getJti_tokenWithoutJtiClaim_fallsBackToHashOfRawToken() {
        String tokenWithoutJti = buildTokenWithoutJti("1");

        String jti = jwtTokenProvider.getJti(tokenWithoutJti);

        assertThat(jti).isEqualTo(TokenHasher.hash(tokenWithoutJti));
        assertThat(jti).hasSize(64); // SHA-256 hex 인코딩은 항상 64자
    }

    @Test
    @DisplayName("jti가 없는 서로 다른 두 토큰은 대체 식별자도 서로 달라 블랙리스트 키가 충돌하지 않는다")
    void getJti_differentTokensWithoutJtiClaim_produceDifferentFallbackIds() {
        String tokenA = buildTokenWithoutJti("1");
        String tokenB = buildTokenWithoutJti("2");

        assertThat(jwtTokenProvider.getJti(tokenA)).isNotEqualTo(jwtTokenProvider.getJti(tokenB));
    }

    @Test
    @DisplayName("getIssuedAt은 발급 시각(iat)을 반환한다 — 비밀번호 재설정 이전 발급 여부 판단(PasswordChangeInvalidationPort)에 사용")
    void getIssuedAt_returnsIssuedAtClaim() {
        Date before = new Date(System.currentTimeMillis() / 1000 * 1000); // JWT iat는 초 단위로 잘린다
        String token = jwtTokenProvider.generateAccessToken(1L, "guardian@example.com", "GUARDIAN");

        Date issuedAt = jwtTokenProvider.getIssuedAt(token);

        assertThat(issuedAt).isNotNull();
        assertThat(issuedAt).isAfterOrEqualTo(before);
    }
}
