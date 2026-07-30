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
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.mail.EmailGateway;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private MemberTokenRepository memberTokenRepository;

    @Mock
    private EmailGateway emailGateway;

    @InjectMocks
    private EmailVerificationService emailVerificationService;

    @Test
    @DisplayName("인증 메일 발송 시 토큰을 발급하고 그 토큰이 포함된 본문으로 메일을 보낸다")
    void sendVerificationEmail_issuesTokenAndSendsMail() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberTokenRepository.issueEmailVerificationToken(eq(1L), any(Duration.class)))
                .willReturn("test-token");

        emailVerificationService.sendVerificationEmail(member);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(eq("guardian@example.com"), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("test-token");
    }

    @Test
    @DisplayName("메일 발송 중 예외가 나도 흡수하고 전파하지 않는다(회원가입 자체를 막지 않기 위함)")
    void sendVerificationEmail_swallowsGatewayException() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberTokenRepository.issueEmailVerificationToken(eq(1L), any(Duration.class)))
                .willReturn("test-token");
        willThrow(new RuntimeException("SMTP down")).given(emailGateway).send(anyString(), anyString(), anyString());

        assertThatCode(() -> emailVerificationService.sendVerificationEmail(member))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유효한 토큰이면 해당 회원의 이메일 인증을 완료 처리한다")
    void verifyEmail_success() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberTokenRepository.consumeEmailVerificationToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        emailVerificationService.verifyEmail("valid-token");

        assertThat(member.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("토큰이 없거나 만료됐으면 INVALID_OR_EXPIRED_TOKEN 예외를 던진다")
    void verifyEmail_invalidToken() {
        given(memberTokenRepository.consumeEmailVerificationToken("bad-token")).willReturn(Optional.empty());

        assertThatThrownBy(() -> emailVerificationService.verifyEmail("bad-token"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN));
    }

    @Test
    @DisplayName("토큰은 유효하지만 그 사이 회원이 존재하지 않으면 MEMBER_NOT_FOUND 예외를 던진다")
    void verifyEmail_memberNotFound() {
        given(memberTokenRepository.consumeEmailVerificationToken("valid-token")).willReturn(Optional.of(1L));
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> emailVerificationService.verifyEmail("valid-token"))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("재발송은 이미 인증된 회원에게는 메일을 보내지 않는다")
    void resendVerificationEmail_alreadyVerified_doesNothing() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        member.verifyEmail();
        given(memberRepository.findByEmail("guardian@example.com")).willReturn(Optional.of(member));

        emailVerificationService.resendVerificationEmail("guardian@example.com");

        verify(emailGateway, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("재발송은 존재하지 않는 이메일에도 예외 없이 조용히 반환한다(계정 존재 여부 노출 방지)")
    void resendVerificationEmail_memberNotFound_doesNothingSilently() {
        given(memberRepository.findByEmail("unknown@example.com")).willReturn(Optional.empty());

        assertThatCode(() -> emailVerificationService.resendVerificationEmail("unknown@example.com"))
                .doesNotThrowAnyException();
        verify(emailGateway, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("재발송은 미인증 회원에게는 새 토큰을 발급해 메일을 보낸다")
    void resendVerificationEmail_unverified_sendsMail() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberRepository.findByEmail("guardian@example.com")).willReturn(Optional.of(member));
        given(memberTokenRepository.issueEmailVerificationToken(eq(1L), any(Duration.class)))
                .willReturn("new-token");

        emailVerificationService.resendVerificationEmail("guardian@example.com");

        verify(emailGateway).send(eq("guardian@example.com"), anyString(), anyString());
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
