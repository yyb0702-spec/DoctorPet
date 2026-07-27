package com.doctorpet.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Access/Refresh Token 발급·검증을 담당한다.
 * 실제 로그인/재발급 시나리오(Redis 저장, 회전, 재사용 감지)는 member 도메인에서 이 클래스를 사용해 구현한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";

    private final JwtProperties jwtProperties;

    private SecretKey key;

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long memberId, String email, String role) {
        return generateToken(memberId, email, role, TokenType.ACCESS, jwtProperties.getAccessTokenExpiration());
    }

    public String generateRefreshToken(Long memberId, String email, String role) {
        return generateToken(memberId, email, role, TokenType.REFRESH, jwtProperties.getRefreshTokenExpiration());
    }

    private String generateToken(Long memberId, String email, String role, TokenType tokenType, long expirationMillis) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMillis);

        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TOKEN_TYPE, tokenType.name())
                // JWT의 iat/exp(NumericDate)는 초 단위로 잘린다. 같은 회원에게 같은 초 안에서
                // 토큰을 두 번 발급하면(예: 재발급 연타, 동시 요청) subject·claim·iat·exp가 전부
                // 같아져 서명까지 동일한 "완전히 같은 문자열"이 나올 수 있다 — Refresh Token 회전에서
                // 이러면 새 토큰이 옛 토큰과 같아져 compare-and-set이 사실상 무의미해진다(리뷰 지적,
                // 동시 재발급 테스트에서 실제로 재현됨). jti(무작위 UUID)로 토큰마다 유일성을 보장한다.
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("유효하지 않은 JWT: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 토큰의 용도(ACCESS/REFRESH)를 반환한다. {@link #validateToken(String)}으로 서명·만료를
     * 먼저 검증한 뒤에만 호출해야 한다 — 이 메서드 자체는 파싱 실패 시 예외를 그대로 던진다.
     * 클레임이 없거나(구버전 토큰) 값이 깨졌으면 알 수 없는 용도로 취급해 어느 쪽 검사에도
     * 통과하지 못하도록 {@code null}을 반환한다(fail-closed).
     */
    public TokenType getTokenType(String token) {
        Claims claims = parseClaims(token);
        String tokenType = claims.get(CLAIM_TOKEN_TYPE, String.class);
        try {
            return tokenType == null ? null : TokenType.valueOf(tokenType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public MemberPrincipal getMemberPrincipal(String token) {
        Claims claims = parseClaims(token);

        return new MemberPrincipal(
                Long.valueOf(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                claims.get(CLAIM_ROLE, String.class)
        );
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
