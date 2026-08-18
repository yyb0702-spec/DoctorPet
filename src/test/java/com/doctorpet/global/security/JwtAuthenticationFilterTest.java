package com.doctorpet.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import jakarta.servlet.FilterChain;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 리뷰에서 지적된 P1 대응: Refresh Token으로 보호 API를 호출해도 인증되지 않아야 한다.
 * addFilters=false를 쓰는 컨트롤러 슬라이스 테스트로는 이 필터 자체의 동작을 검증할 수 없어
 * 별도 단위 테스트로 분리했다.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private MemberBlacklistPort memberBlacklistPort;

    @Mock
    private AccessTokenBlacklistPort accessTokenBlacklistPort;

    @Mock
    private PasswordChangeInvalidationPort passwordChangeInvalidationPort;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final FilterChain filterChain = (req, res) -> { };

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Access Token이면 SecurityContext에 인증 정보를 설정한다")
    void doFilterInternal_accessToken_authenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN");
        Date issuedAt = new Date();

        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(memberBlacklistPort.isBlacklisted(1L)).willReturn(false);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti-1234");
        given(accessTokenBlacklistPort.isBlacklisted("jti-1234")).willReturn(false);
        given(jwtTokenProvider.getIssuedAt("access-token")).willReturn(issuedAt);
        given(passwordChangeInvalidationPort.isTokenInvalidatedByPasswordChange(1L, issuedAt)).willReturn(false);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
    }

    @Test
    @DisplayName("서명·만료·타입·블랙리스트는 전부 통과해도, 비밀번호 재설정 이전에 발급된 토큰이면 SecurityContext를 설정하지 않는다(기능 구멍 점검 대응)")
    void doFilterInternal_tokenIssuedBeforePasswordChange_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN");
        Date issuedAt = new Date();

        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(memberBlacklistPort.isBlacklisted(1L)).willReturn(false);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti-1234");
        given(accessTokenBlacklistPort.isBlacklisted("jti-1234")).willReturn(false);
        given(jwtTokenProvider.getIssuedAt("access-token")).willReturn(issuedAt);
        given(passwordChangeInvalidationPort.isTokenInvalidatedByPasswordChange(1L, issuedAt)).willReturn(true);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("서명·만료·타입은 유효하고 회원 블랙리스트에도 없지만, 로그아웃한 그 토큰(jti)이 블랙리스트에 있으면 SecurityContext를 설정하지 않는다(#124)")
    void doFilterInternal_blacklistedAccessToken_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN");

        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(memberBlacklistPort.isBlacklisted(1L)).willReturn(false);
        given(jwtTokenProvider.getJti("access-token")).willReturn("jti-1234");
        given(accessTokenBlacklistPort.isBlacklisted("jti-1234")).willReturn(true);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("서명·만료·타입은 유효해도 탈퇴 등으로 블랙리스트에 있으면 SecurityContext를 설정하지 않는다(리뷰 지적 P1 대응)")
    void doFilterInternal_blacklistedMember_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MemberPrincipal principal = new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN");

        given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("access-token")).willReturn(TokenType.ACCESS);
        given(jwtTokenProvider.getMemberPrincipal("access-token")).willReturn(principal);
        given(memberBlacklistPort.isBlacklisted(1L)).willReturn(true);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Refresh Token이면(용도가 다르면) SecurityContext를 설정하지 않는다 — 보호 API 인증에 쓸 수 없다")
    void doFilterInternal_refreshToken_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer refresh-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("refresh-token")).willReturn(TokenType.REFRESH);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 SecurityContext를 설정하지 않고 다음 필터로 넘어간다")
    void doFilterInternal_noToken_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
