package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.MemberTokenRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.mail.EmailGateway;
import com.doctorpet.global.security.JwtProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberTokenRepository memberTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private EmailGateway emailGateway;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private PasswordResetService passwordResetService;

    @Test
    @DisplayName("가입된 이메일이면 토큰을 발급해 그 토큰이 포함된 재설정 메일을 보낸다")
    void requestPasswordReset_existingMember_sendsMail() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberRepository.findByEmail("guardian@example.com")).willReturn(Optional.of(member));
        given(memberTokenRepository.issuePasswordResetToken(eq(1L), any(Duration.class)))
                .willReturn("reset-token");

        passwordResetService.requestPasswordReset("guardian@example.com");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(eq("guardian@example.com"), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("reset-token");
    }

    @Test
    @DisplayName("가입되지 않은 이메일이어도 예외 없이 조용히 반환한다(계정 존재 여부 노출 방지)")
    void requestPasswordReset_memberNotFound_doesNothingSilently() {
        given(memberRepository.findByEmail("unknown@example.com")).willReturn(Optional.empty());

        assertThatCode(() -> passwordResetService.requestPasswordReset("unknown@example.com"))
                .doesNotThrowAnyException();
        verify(emailGateway, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("메일 발송 중 예외가 나도 흡수하고 전파하지 않는다")
    void requestPasswordReset_swallowsGatewayException() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberRepository.findByEmail("guardian@example.com")).willReturn(Optional.of(member));
        given(memberTokenRepository.issuePasswordResetToken(eq(1L), any(Duration.class)))
                .willReturn("reset-token");
        willThrow(new RuntimeException("SMTP down")).given(emailGateway).send(anyString(), anyString(), anyString());

        assertThatCode(() -> passwordResetService.requestPasswordReset("guardian@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("토큰 발급(Redis) 자체가 실패해도 흡수하고 전파하지 않는다(계정 존재 여부 비노출 계약 유지)")
    void requestPasswordReset_swallowsTokenIssuanceException() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberRepository.findByEmail("guardian@example.com")).willReturn(Optional.of(member));
        willThrow(new RuntimeException("Redis down"))
                .given(memberTokenRepository).issuePasswordResetToken(eq(1L), any(Duration.class));

        assertThatCode(() -> passwordResetService.requestPasswordReset("guardian@example.com"))
                .doesNotThrowAnyException();
        verify(emailGateway, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("유효한 토큰과 새 비밀번호로 확인하면 비밀번호를 교체하고 잠금을 해제한다(SA \"재설정 성공 시 잠금 해제\")")
    void confirmPasswordReset_success() {
        Member member = Member.createGuardian("guardian@example.com", "old-encoded-password", "보호자닉네임");
        setId(member, 1L);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            member.recordLoginFailure(now);
        }
        given(memberTokenRepository.consumePasswordResetToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.encode("newPassword1234")).willReturn("new-encoded-password");

        passwordResetService.confirmPasswordReset("valid-token", "newPassword1234");

        assertThat(member.getPassword()).isEqualTo("new-encoded-password");
        assertThat(member.isLocked()).isFalse();
        assertThat(member.getFailedLoginAttempts()).isZero();
    }

    @Test
    @DisplayName("비밀번호 재설정 성공 시 기존 Refresh Token(세션)을 무효화한다 — 계정 탈취 복구 시나리오 대응(기능 구멍 점검)")
    void confirmPasswordReset_success_invalidatesExistingSession() {
        Member member = Member.createGuardian("guardian@example.com", "old-encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberTokenRepository.consumePasswordResetToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.encode("newPassword1234")).willReturn("new-encoded-password");

        passwordResetService.confirmPasswordReset("valid-token", "newPassword1234");

        verify(refreshTokenRepository).deleteByMemberId(1L);
    }

    @Test
    @DisplayName("비밀번호 재설정 성공 시 재설정 이전에 발급된 Access Token도 무효화한다 — Refresh Token 삭제만으로는 못 막던 구멍(재검토 대응)")
    void confirmPasswordReset_success_invalidatesAccessTokensIssuedBeforeReset() {
        Member member = Member.createGuardian("guardian@example.com", "old-encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberTokenRepository.consumePasswordResetToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.encode("newPassword1234")).willReturn("new-encoded-password");
        given(jwtProperties.getAccessTokenExpiration()).willReturn(3_600_000L);

        passwordResetService.confirmPasswordReset("valid-token", "newPassword1234");

        verify(refreshTokenRepository).invalidateTokensIssuedBeforeNow(1L, Duration.ofMillis(3_600_000L));
    }

    @Test
    @DisplayName("토큰이 없거나 만료됐으면 INVALID_OR_EXPIRED_TOKEN 예외를 던지고, 세션을 건드리지 않는다")
    void confirmPasswordReset_invalidToken() {
        given(memberTokenRepository.consumePasswordResetToken("bad-token")).willReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.confirmPasswordReset("bad-token", "newPassword1234"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN));
        verify(refreshTokenRepository, never()).deleteByMemberId(any());
    }

    @Test
    @DisplayName("토큰은 유효하지만 그 사이 회원이 존재하지 않으면 MEMBER_NOT_FOUND 예외를 던지고, 세션을 건드리지 않는다")
    void confirmPasswordReset_memberNotFound() {
        given(memberTokenRepository.consumePasswordResetToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.confirmPasswordReset("valid-token", "newPassword1234"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        verify(refreshTokenRepository, never()).deleteByMemberId(any());
    }

    @Test
    @DisplayName("login()의 findByEmailForUpdate()와 같은 행 락(findByIdForUpdate)으로 조회한다 — 재설정·로그인 직렬화(리뷰 지적)")
    void confirmPasswordReset_usesRowLockSharedWithLogin() {
        Member member = Member.createGuardian("guardian@example.com", "old-encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberTokenRepository.consumePasswordResetToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(passwordEncoder.encode("newPassword1234")).willReturn("new-encoded-password");

        passwordResetService.confirmPasswordReset("valid-token", "newPassword1234");

        verify(memberRepository).findByIdForUpdate(1L);
        verify(memberRepository, never()).findById(any());
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
