package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.event.MemberSignedUpEvent;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtProperties;
import com.doctorpet.global.security.JwtTokenProvider;
import com.doctorpet.global.security.MemberPrincipal;
import com.doctorpet.global.security.TokenType;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        // 백로그 P2 — 가입 직후 이메일 인증 메일 발송 훅. 트랜잭션 커밋 이후에만 발송되도록
        // 이벤트를 발행하는 것까지만 여기서 검증한다(실제 발송은 MemberSignupEventListener,
        // AFTER_COMMIT 시점 처리는 리뷰 대응 — 리뷰 대응 이력 참고).
        verify(eventPublisher).publishEvent(new MemberSignedUpEvent(savedMember));
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
    @DisplayName("existsByEmail 사전 체크 이후 경쟁 상태로 이메일 UNIQUE 제약에 걸려도, 사전 체크와 같은 DUPLICATE_EMAIL로 응답한다")
    void signup_duplicateEmailRaceCondition_mapsToSameErrorAsPrecheck() {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "보호자닉네임");
        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(memberRepository.save(any(Member.class)))
                .willThrow(new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLIntegrityConstraintViolationException(
                                "Duplicate entry 'guardian@example.com' for key 'members.uk_members_email'",
                                "23000",
                                1062)
                ));

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    @DisplayName("이메일 제약이 아닌 다른 무결성 위반이면 도메인 에러로 바꾸지 않고 그대로 전파한다")
    void signup_otherIntegrityViolation_propagatesAsIs() {
        SignupRequest request = new SignupRequest("guardian@example.com", "password1234", "보호자닉네임");
        DataIntegrityViolationException otherViolation = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLIntegrityConstraintViolationException(
                        "Column 'nickname' cannot be null", "23000", 1048)
        );
        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn("encoded-password");
        given(memberRepository.save(any(Member.class))).willThrow(otherViolation);

        assertThatThrownBy(() -> authService.signup(request))
                .isSameAs(otherViolation);
    }

    @Test
    @DisplayName("이메일·비밀번호가 맞으면 토큰을 발급하고 Redis에 Refresh Token을 저장한다")
    void login_success() {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        member.verifyEmail(); // 이메일 인증 필수(백로그 P2) — 미인증이면 이 성공 시나리오 자체가 불가능하다.

        given(memberRepository.findByEmailForUpdate(request.email())).willReturn(Optional.of(member));
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
        given(memberRepository.findByEmailForUpdate(request.email())).willReturn(Optional.empty());

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

        given(memberRepository.findByEmailForUpdate(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPassword())).willReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_CREDENTIALS));
        assertThat(member.getFailedLoginAttempts()).isEqualTo(1);
        verify(jwtTokenProvider, never()).generateAccessToken(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("이메일·비밀번호는 맞지만 이메일 인증 전이면 EMAIL_NOT_VERIFIED 예외를 던지고, 토큰은 발급하지 않는다(백로그 P2)")
    void login_emailNotVerified() {
        LoginRequest request = new LoginRequest("guardian@example.com", "password1234");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        // emailVerified 기본값은 false — 인증 안 함.

        given(memberRepository.findByEmailForUpdate(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPassword())).willReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.EMAIL_NOT_VERIFIED));
        // 비밀번호 자체는 맞았으므로 실패 횟수는 그대로 초기화된다(recordLoginSuccess가 먼저 실행됨) —
        // 미인증 상태는 "틀린 로그인 시도"로 카운트하지 않는다.
        assertThat(member.getFailedLoginAttempts()).isZero();
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

        given(memberRepository.findByEmailForUpdate(request.email())).willReturn(Optional.of(member));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.ACCOUNT_LOCKED));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis에 저장된 값과 일치하는 Refresh Token이면 새 토큰 쌍을 발급하고 Redis 값을 원자적으로 교체한다(회전)")
    void reissue_success() {
        ReissueRequest request = new ReissueRequest("old-refresh-token");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);

        given(jwtTokenProvider.validateToken("old-refresh-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("old-refresh-token")).willReturn(TokenType.REFRESH);
        given(jwtTokenProvider.getMemberPrincipal("old-refresh-token"))
                .willReturn(new MemberPrincipal(1L, member.getEmail(), "GUARDIAN"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(jwtTokenProvider.generateAccessToken(1L, member.getEmail(), "GUARDIAN")).willReturn("new-access-token");
        given(jwtTokenProvider.generateRefreshToken(1L, member.getEmail(), "GUARDIAN")).willReturn("new-refresh-token");
        given(jwtProperties.getRefreshTokenExpiration()).willReturn(1_209_600_000L);
        given(refreshTokenRepository.tryLock(eq(1L), anyString())).willReturn(true);
        given(refreshTokenRepository.rotateIfMatches(
                1L, "old-refresh-token", "new-refresh-token", Duration.ofMillis(1_209_600_000L)
        )).willReturn(true);

        LoginResponse response = authService.reissue(request);

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        verify(refreshTokenRepository, never()).deleteByMemberId(anyLong());
        // 성공하든 실패하든 락은 항상(finally) 해제돼야 한다.
        verify(refreshTokenRepository).unlock(eq(1L), anyString());
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
    @DisplayName("Access Token으로 재발급을 시도하면 INVALID_REFRESH_TOKEN 예외를 던진다(용도 구분)")
    void reissue_accessTokenRejected() {
        ReissueRequest request = new ReissueRequest("some-access-token");
        given(jwtTokenProvider.validateToken("some-access-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("some-access-token")).willReturn(TokenType.ACCESS);

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN));
        verify(memberRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("Redis 원자 교체가 실패하면(이미 회전된 토큰 재사용) 세션을 삭제하고 REFRESH_TOKEN_REUSED 예외를 던진다")
    void reissue_tokenReused() {
        ReissueRequest request = new ReissueRequest("already-rotated-token");
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);

        given(jwtTokenProvider.validateToken("already-rotated-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("already-rotated-token")).willReturn(TokenType.REFRESH);
        given(jwtTokenProvider.getMemberPrincipal("already-rotated-token"))
                .willReturn(new MemberPrincipal(1L, member.getEmail(), "GUARDIAN"));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(jwtTokenProvider.generateAccessToken(1L, member.getEmail(), "GUARDIAN")).willReturn("new-access-token");
        given(jwtTokenProvider.generateRefreshToken(1L, member.getEmail(), "GUARDIAN")).willReturn("new-refresh-token");
        given(jwtProperties.getRefreshTokenExpiration()).willReturn(1_209_600_000L);
        given(refreshTokenRepository.tryLock(eq(1L), anyString())).willReturn(true);
        given(refreshTokenRepository.rotateIfMatches(
                1L, "already-rotated-token", "new-refresh-token", Duration.ofMillis(1_209_600_000L)
        )).willReturn(false);

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.REFRESH_TOKEN_REUSED));
        verify(refreshTokenRepository).deleteByMemberId(1L);
        verify(refreshTokenRepository).unlock(eq(1L), anyString());
    }

    @Test
    @DisplayName("같은 회원에 대한 다른 재발급 요청이 이미 처리 중(락 선점)이면, CAS를 시도하지도 않고 REISSUE_IN_PROGRESS 예외를 던진다")
    void reissue_lockNotAcquired_throwsReissueInProgress() {
        // 리뷰 대응: 이전에는 CAS 실패를 5초 유예 창으로 "동시 중복 요청"과 "진짜 재사용"을
        // 구분했는데, 그 창 안에서는 진짜 탈취 토큰 재사용도 놓칠 수 있어 보안 약화로 지적됐다.
        // 지금은 같은 회원의 재발급을 락으로 아예 직렬화해서, 동시 요청 중 락을 못 얻은 쪽은
        // CAS 단계까지 가지도 않고 즉시 실패한다 — 세션 상태를 전혀 건드리지 않는다.
        ReissueRequest request = new ReissueRequest("old-refresh-token");

        given(jwtTokenProvider.validateToken("old-refresh-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("old-refresh-token")).willReturn(TokenType.REFRESH);
        given(jwtTokenProvider.getMemberPrincipal("old-refresh-token"))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(refreshTokenRepository.tryLock(eq(1L), anyString())).willReturn(false);

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.REISSUE_IN_PROGRESS));

        // 핵심 회귀 검증: 락을 못 얻었으면 회원 조회·토큰 발급·CAS·세션 삭제 중 아무것도 하지 않는다.
        verify(memberRepository, never()).findById(anyLong());
        verify(refreshTokenRepository, never()).rotateIfMatches(anyLong(), anyString(), anyString(), any(Duration.class));
        verify(refreshTokenRepository, never()).deleteByMemberId(anyLong());
        // 락을 애초에 얻지 못했으므로 해제할 필요도, 호출도 없다.
        verify(refreshTokenRepository, never()).unlock(anyLong(), anyString());
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원이 탈퇴 등으로 존재하지 않으면 INVALID_REFRESH_TOKEN 예외를 던진다")
    void reissue_memberNotFound() {
        ReissueRequest request = new ReissueRequest("valid-token");
        given(jwtTokenProvider.validateToken("valid-token")).willReturn(true);
        given(jwtTokenProvider.getTokenType("valid-token")).willReturn(TokenType.REFRESH);
        given(jwtTokenProvider.getMemberPrincipal("valid-token"))
                .willReturn(new MemberPrincipal(1L, "guardian@example.com", "GUARDIAN"));
        given(refreshTokenRepository.tryLock(eq(1L), anyString())).willReturn(true);
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.reissue(request))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_REFRESH_TOKEN));
        // 회원 조회 단계에서 실패해도 락은 finally에서 반드시 해제돼야 한다.
        verify(refreshTokenRepository).unlock(eq(1L), anyString());
    }

    @Test
    @DisplayName("로그아웃하면 해당 회원의 Redis Refresh Token을 삭제한다")
    void logout_success() {
        authService.logout(1L);

        verify(refreshTokenRepository).deleteByMemberId(1L);
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
