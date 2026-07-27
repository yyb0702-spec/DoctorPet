package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.global.exception.CustomException;
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
    private PasswordEncoder passwordEncoder;

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
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
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
