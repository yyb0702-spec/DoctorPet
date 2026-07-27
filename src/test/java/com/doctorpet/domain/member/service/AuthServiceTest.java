package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtProperties;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("이메일이 중복되지 않으면 비밀번호를 암호화해 회원을 저장하고 memberId를 반환한다")
    void signup_success() {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "보호자닉네임");
        Member savedMember = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(savedMember, 1L);

        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(memberRepository.save(any(Member.class))).willReturn(savedMember);

        SignupResponse response = authService.signup(request);

        assertThat(response.memberId()).isEqualTo(1L);
        verify(passwordEncoder).encode("password1234");
    }

    @Test
    @DisplayName("이미 사용 중인(활성 회원) 이메일이면 DUPLICATE_EMAIL 예외를 던진다")
    void signup_duplicateEmail() {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "보호자닉네임");
        given(memberRepository.existsByEmail(request.email())).willReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    @DisplayName("이메일·비밀번호가 맞으면 토큰을 발급하고 Redis에 Refresh Token을 저장한다")
    void login_success() {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);

        given(memberRepository.findByEmail(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPassword())).willReturn(true);
        given(jwtTokenProvider.generateAccessToken(1L, member.getEmail(), "GUARDIAN")).willReturn("access-token");
        given(jwtTokenProvider.generateRefreshToken(1L, member.getEmail(), "GUARDIAN")).willReturn("refresh-token");
        given(jwtProperties.getRefreshTokenExpiration()).willReturn(1_209_600_000L);

        LoginResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(member.getFailedLoginAttempts()).isZero();
        verify(refreshTokenRepository).save(1L, "refresh-token", Duration.ofMillis(1_209_600_000L));
    }

    @Test
    @DisplayName("가입되지 않은 이메일이면 INVALID_CREDENTIALS 예외를 던진다(계정 존재 여부 노출 방지)")
    void login_emailNotFound() {
        LoginRequest request = new LoginRequest("unknown@example.com", "password1234");
        given(memberRepository.findByEmail(request.email())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 INVALID_CREDENTIALS 예외를 던지고 실패 횟수를 올린다")
    void login_wrongPassword() {
        LoginRequest request = new LoginRequest("guardian@example.com", "wrong-password");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);

        given(memberRepository.findByEmail(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPassword())).willReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_CREDENTIALS));
        assertThat(member.getFailedLoginAttempts()).isEqualTo(1);
        verify(jwtTokenProvider, never()).generateAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("로그인 실패 5회로 잠긴 계정은 비밀번호가 맞아도 ACCOUNT_LOCKED 예외를 던진다")
    void login_accountLocked() {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        for (int i = 0; i < 5; i++) {
            member.recordLoginFailure(java.time.LocalDateTime.now());
        }

        given(memberRepository.findByEmail(request.email())).willReturn(Optional.of(member));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.ACCOUNT_LOCKED));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis에 저장된 값과 일치하는 Refresh Token이면 새 토큰 쌍을 발급하고 Redis 값을 덮어쓴다(회전)")
    void reissue_success() {
        ReissueRequest request = new ReissueRequest("old-refresh-token");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);

        given(jwtTokenProvider.validateToken("old-refresh-token")).willReturn(true);
        given(jwtTokenProvider.getMemberPrincipal("old-refresh-token"))
                .willReturn(new MemberPrincipal(1L, member.getEmail(), "GUARDIAN"));
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.of("old-refresh-token"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(jwtTokenProvider.generateAccessToken(1L, member.getEmail(), "GUARDIAN")).willReturn("new-access-token");
        given(jwtTokenProvider.generateRefreshToken(1L, member.getEmail(), "GUARDIAN")).willReturn("new-refresh-token");
        given(jwtProperties.getRefreshTokenExpiration()).willReturn(1_209_600_000L);

        LoginResponse response = authService.reissue(request);

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        verify(refreshTokenRepository).save(1L, "new-refresh-token", Duration.ofMillis(1_209_600_000L));
        verify(refreshTokenRepository, never()).deleteByMemberId(anyLong());
    }

    @Test
    @DisplayName("서명이 위조됐거나 만료된 토큰이면 INVALID_REFRESH_TOKEN 예외를 던진다")
    void reissue_invalidToken() {
        ReissueRequest request = new ReissueRequest("broken-token");
        given(jwtTokenProvider.validateToken("broken-token")).willReturn(false);

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("Redis에 저장된 값과 다르면(이미 회전된 토큰 재사용) 세션을 삭제하고 REFRESH_TOKEN_REUSED 예외를 던진다")
    void reissue_tokenReused() {
        ReissueRequest request = new ReissueRequest("already-rotated-token");
        given(jwtTokenProvider.validateToken("already-rotated-token")).willReturn(true);
        given(jwtTokenProvider.getMemberPrincipal("already-rotated-token"))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.of("current-valid-token"));

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.REFRESH_TOKEN_REUSED));
        verify(refreshTokenRepository).deleteByMemberId(1L);
    }

    @Test
    @DisplayName("Redis에 저장된 값이 아예 없으면(만료·로그아웃 등) 세션을 삭제하고 REFRESH_TOKEN_REUSED 예외를 던진다")
    void reissue_noStoredToken() {
        ReissueRequest request = new ReissueRequest("some-token");
        given(jwtTokenProvider.validateToken("some-token")).willReturn(true);
        given(jwtTokenProvider.getMemberPrincipal("some-token"))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.REFRESH_TOKEN_REUSED));
        verify(refreshTokenRepository).deleteByMemberId(1L);
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원이 탈퇴 등으로 존재하지 않으면 INVALID_REFRESH_TOKEN 예외를 던진다")
    void reissue_memberNotFound() {
        ReissueRequest request = new ReissueRequest("valid-token");
        given(jwtTokenProvider.validateToken("valid-token")).willReturn(true);
        given(jwtTokenProvider.getMemberPrincipal("valid-token"))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(refreshTokenRepository.findByMemberId(1L)).willReturn(Optional.of("valid-token"));
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN));
    }

    private void setId(Member member, Long id) {
        try {
            var field = Member.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(member, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
