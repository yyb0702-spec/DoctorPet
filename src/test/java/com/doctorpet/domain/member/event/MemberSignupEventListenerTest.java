package com.doctorpet.domain.member.event;

import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.service.EmailVerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MemberSignedUpEvent를 받아 인증 메일 발송을 위임하는지만 검증한다. AFTER_COMMIT에서만
 * 실제로 호출되는지(트랜잭션 경계 자체)는 이 단위 테스트로는 검증할 수 없다 — 리스너 등록/트랜잭션
 * 연동은 signup() 관련 통합 테스트가 있다면 그쪽 책임이다.
 */
@ExtendWith(MockitoExtension.class)
class MemberSignupEventListenerTest {

    @Mock
    private EmailVerificationService emailVerificationService;

    @InjectMocks
    private MemberSignupEventListener listener;

    @Test
    @DisplayName("이벤트를 받으면 해당 회원에게 인증 메일 발송을 위임한다")
    void handleMemberSignedUp_delegatesToEmailVerificationService() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");

        listener.handleMemberSignedUp(new MemberSignedUpEvent(member));

        verify(emailVerificationService).sendVerificationEmail(member);
    }
}
