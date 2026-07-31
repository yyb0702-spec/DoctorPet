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
