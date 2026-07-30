package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    @DisplayName("존재하는 회원이면 내 정보를 반환한다")
    void getMyInfo_success() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        MemberResponse response = memberService.getMyInfo(1L);

        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("guardian@example.com");
        assertThat(response.nickname()).isEqualTo("보호자닉네임");
        assertThat(response.role()).isEqualTo(MemberRole.GUARDIAN);
        assertThat(response.hospitalId()).isNull();
    }

    @Test
    @DisplayName("토큰은 유효하지만 그 사이 탈퇴 등으로 회원이 존재하지 않으면 MEMBER_NOT_FOUND 예외를 던진다")
    void getMyInfo_memberNotFound() {
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.getMyInfo(1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("존재하는(활성) 회원이면 예외 없이 통과한다")
    void assertActiveMember_success() {
        given(memberRepository.existsById(1L)).willReturn(true);

        memberService.assertActiveMember(1L);
    }

    @Test
    @DisplayName("존재하지 않거나 탈퇴한 회원이면 MEMBER_NOT_FOUND를 던진다")
    void assertActiveMember_memberNotFound() {
        given(memberRepository.existsById(1L)).willReturn(false);

        assertThatThrownBy(() -> memberService.assertActiveMember(1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("존재하는 회원이면 withdraw()를 호출해 탈퇴 처리한다")
    void withdraw_success() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        memberService.withdraw(1L);

        assertThat(member.getEmail()).isEqualTo("withdrawn_1@deleted.doctorpet");
        assertThat(member.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 회원을 탈퇴시키려 하면 MEMBER_NOT_FOUND를 던진다")
    void withdraw_memberNotFound() {
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.withdraw(1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
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
