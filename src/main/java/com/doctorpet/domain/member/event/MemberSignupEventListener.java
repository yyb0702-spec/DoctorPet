package com.doctorpet.domain.member.event;

import com.doctorpet.domain.member.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * MemberSignedUpEvent를 AFTER_COMMIT 시점에만 받아 인증 메일을 발송한다. 반드시 AFTER_COMMIT을
 * 써야 하는 이유는 MemberSignedUpEvent의 주석 참고 — 기본값(BEFORE_COMMIT 성격의 즉시 처리)으로
 * 두면 signup() 트랜잭션이 커밋되기 전에 SMTP를 호출하게 돼, 이 이벤트를 도입한 의미가 없어진다.
 *
 * 이벤트가 트랜잭션 없이 발행되면(예: 트랜잭션 밖에서 호출되는 테스트 코드) 기본 설정상 이 리스너
 * 자체가 호출되지 않는다 — signup()은 항상 @Transactional이므로 운영 경로에서는 문제가 없다.
 */
@Component
@RequiredArgsConstructor
public class MemberSignupEventListener {

    private final EmailVerificationService emailVerificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMemberSignedUp(MemberSignedUpEvent event) {
        emailVerificationService.sendVerificationEmail(event.member());
    }
}
