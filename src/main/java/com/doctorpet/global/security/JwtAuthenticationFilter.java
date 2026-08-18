package com.doctorpet.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final MemberBlacklistPort memberBlacklistPort;
    private final AccessTokenBlacklistPort accessTokenBlacklistPort;
    private final PasswordChangeInvalidationPort passwordChangeInvalidationPort;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = resolveToken(request);

        // Access Token만 인증에 사용한다. Refresh Token은 만료 시간 외 클레임 구조가 같아
        // tokenType을 확인하지 않으면 보호 API의 인증 토큰으로도 통과할 수 있다.
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)
                && jwtTokenProvider.getTokenType(token) == TokenType.ACCESS) {
            MemberPrincipal principal = jwtTokenProvider.getMemberPrincipal(token);

            // 탈퇴 회원 Access Token 블랙리스트(리뷰 지적 P1 대응) — JWT 자체의 서명·만료는
            // 여전히 유효해도, 탈퇴 시 MemberWithdrawalApplicationService가 Redis에 남긴
            // 블랙리스트에 있으면 인증하지 않는다. SecurityContext를 설정하지 않고 그냥 다음
            // 필터로 넘기면, 인증이 필요한 API는 Spring Security가 401로 거부한다(permitAll
            // 엔드포인트는 원래도 인증 없이 통과하므로 영향 없다).
            if (memberBlacklistPort.isBlacklisted(principal.memberId())) {
                filterChain.doFilter(request, response);
                return;
            }

            // 로그아웃된 토큰 블랙리스트(#124) — 탈퇴처럼 회원 전체를 막는 게 아니라, 로그아웃한
            // 그 토큰(jti) 한 장만 걸러낸다. 그래야 로그아웃 직후 재로그인으로 받은 새 토큰은
            // 같은 memberId라도 영향을 받지 않는다(AccessTokenBlacklistPort 참고).
            if (accessTokenBlacklistPort.isBlacklisted(jwtTokenProvider.getJti(token))) {
                filterChain.doFilter(request, response);
                return;
            }

            // 비밀번호 재설정 이전에 발급된 Access Token 차단(기능 구멍 점검 대응,
            // PasswordChangeInvalidationPort 참고) — 재설정으로 Refresh Token은 지워져도,
            // 이미 발급된 Access Token은 만료 전까지 서명 검증만으로 계속 유효했다. 공격자가
            // 세션(Access Token)을 쥔 채로 계정을 탈취했다면, 피해자가 비밀번호를 바꿔도 공격자는
            // 그 토큰이 자연 만료될 때까지 계속 API를 호출할 수 있었다. 탈퇴(memberBlacklistPort)와
            // 달리 memberId 전체를 막지 않고 "재설정 이전에 발급된 토큰인지"(iat 비교)만 걸러내,
            // 재설정 직후 재로그인으로 받은 새 토큰은 영향받지 않는다.
            if (passwordChangeInvalidationPort.isTokenInvalidatedByPasswordChange(
                    principal.memberId(), jwtTokenProvider.getIssuedAt(token))) {
                filterChain.doFilter(request, response);
                return;
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (StringUtils.hasText(header) && header.startsWith(TOKEN_PREFIX)) {
            return header.substring(TOKEN_PREFIX.length());
        }
        return null;
    }
}
