package com.doctorpet.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    /**
     * 토큰의 jti(고유 ID) 클레임을 반환한다. {@link #validateToken(String)}으로 서명·만료를
     * 먼저 검증한 뒤에만 호출해야 한다 — 로그아웃 시 이 특정 토큰만 콕 집어 블랙리스트에 넣기
     * 위해 쓴다(회원 단위 블랙리스트는 탈퇴처럼 "이 회원의 모든 토큰을 영구히 막아야 하는"
     * 경우에만 맞다 — 로그아웃 직후 재로그인하면 새 토큰은 즉시 다시 유효해야 하므로, 회원
     * 단위로 막으면 그 새 토큰까지 같이 막혀버린다).
     *
     * {@link #parseClaimsTolerateExpiry(String)}를 쓰는 이유는 {@link #getRemainingTtl(String)}
     * 문서를 참고.
     *
     * jti 클레임이 없는 토큰이면(리뷰 지적) 원문 토큰 자체의 SHA-256 해시로 대체한다. jti가
     * 없다는 이유로 null을 그대로 반환하면, 호출부가 그걸 그대로 블랙리스트 키에 이어붙여
     * "at-blacklist:null"이라는 하나의 키로 저장한다 — jti 없는 토큰을 가진 아무 사용자나
     * 로그아웃하면, jti 없는 다른 모든 사용자의 아직 유효한 토큰까지 같은 키로 충돌해 함께
     * 차단돼버린다. 토큰 원문 해시는 (해시 충돌을 무시하면) 토큰마다 사실상 고유하고 원문을
     * 복원할 수 없어 안전한 대체 식별자다. (이 프로젝트에서 jti는 최초 배포보다 훨씬 앞서
     * 도입돼 있어 실제로 jti 없는 Access Token이 운영에 존재할 가능성은 사실상 없지만, 비용이
     * 거의 없는 방어적 처리라 남겨둔다.)
     */
    public String getJti(String token) {
        String jti = parseClaimsTolerateExpiry(token).getId();
        return jti != null ? jti : TokenHasher.hash(token);
    }

    /**
     * 토큰이 자연 만료될 때까지 남은 시간을 반환한다. 이미 만료됐다면(호출 시점 경쟁 등)
     * {@link Duration#ZERO}를 반환한다 — 음수 Duration을 Redis TTL로 그대로 넘기면 에러가 나고,
     * 어차피 만료된 토큰은 블랙리스트에 넣을 필요가 없다(서명 검증에서 이미 걸러진다).
     *
     * {@code parseClaims}(엄격 파싱, jjwt가 만료 시 {@link ExpiredJwtException}을 던짐) 대신
     * {@link #parseClaimsTolerateExpiry(String)}를 쓴다(리뷰 지적) — 이 메서드와 {@link #getJti}는
     * 로그아웃 흐름에서, 즉 {@link JwtAuthenticationFilter}가 {@link #validateToken(String)}으로
     * 서명·만료를 이미 확인해 인증까지 마친 "그 토큰"에 대해서만 호출된다. 필터 통과 시점과 이
     * 메서드가 실행되는 시점 사이에는 짧지만 실제로 존재하는 간격이 있어, 그 사이 만료 경계를
     * 넘으면 엄격 파싱은 예외를 던져 로그아웃이 500으로 끝나고, 그 시점엔 이미
     * {@code refreshTokenRepository.deleteByMemberId()}가 먼저 실행된 뒤라 부분 성공 상태만 남는다.
     * 여기서는 서명이 이미 검증된 토큰의 만료 여부만 관대하게 봐주는 것이므로 보안 저하가 아니다
     * (인증 판단에 쓰이는 {@link #getTokenType(String)}·{@link #getMemberPrincipal(String)}는
     * 여전히 엄격한 {@link #parseClaims(String)}를 쓴다 — 거기서 관대해지면 이미 만료된 토큰으로
     * 인증이 통과하는 실제 보안 저하가 된다).
     */
    public Duration getRemainingTtl(String token) {
        Date expiration = parseClaimsTolerateExpiry(token).getExpiration();
        long remainingMillis = expiration.getTime() - System.currentTimeMillis();
        return remainingMillis > 0 ? Duration.ofMillis(remainingMillis) : Duration.ZERO;
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

    /**
     * {@link #parseClaims(String)}과 동일하게 서명은 검증하지만, 만료(exp)만큼은 예외를 던지지
     * 않고 만료된 클레임을 그대로 반환한다. {@link ExpiredJwtException}도 원본 클레임을 담고
     * 있으므로({@code getClaims()}) 서명 검증 자체는 그대로 통과한 뒤의 결과다 — 즉 위조된
     * 토큰은 여전히 걸러지고, "방금 막 만료됐다"는 사실만 허용한다.
     */
    private Claims parseClaimsTolerateExpiry(String token) {
        try {
            return parseClaims(token);
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }
}
