package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.MemberTokenRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.mail.EmailGateway;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 인증(백로그 P2, 회원가입 시 이메일 소유 확인 — A 도메인 결정). 가입 직후 인증 메일을
 * 발송하고, 인증이 완료될 때까지 로그인을 차단한다(AuthService.login()).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final MemberRepository memberRepository;
    private final MemberTokenRepository memberTokenRepository;
    private final EmailGateway emailGateway;

    @Value("${mail.verification.base-url}")
    private String verificationBaseUrl;

    /*
      회원가입 직후 MemberSignupEventListener(AFTER_COMMIT)에서 호출된다. 메일 발송 실패(SMTP
      장애 등)가 회원가입 자체를 롤백시키지 않도록 여기서 예외를 흡수한다 — 사용자는 재발송
      (resendVerificationEmail)으로 복구할 수 있다.

      토큰 발급(Redis) 자체도 이 try 안에 포함한다 — 회원가입 트랜잭션은 이미 커밋된 뒤(AFTER_
      COMMIT)라 여기서 예외가 새어나가면 회원은 실제로 정상 생성됐는데도 signup() 호출자에게는
      요청이 실패한 것처럼 보인다(Spring이 AFTER_COMMIT 리스너의 예외를 원래 호출자에게 다시
      던지기 때문). Redis 장애로 토큰 발급 자체가 안 되는 경우도 "메일 발송 실패"와 같은 방식으로
      흡수하고 로그만 남긴다.
     */
    public void sendVerificationEmail(Member member) {
        try {
            String token = memberTokenRepository.issueEmailVerificationToken(member.getId(), TOKEN_TTL);
            String link = verificationBaseUrl + "?token=" + token;
            emailGateway.send(
                    member.getEmail(),
                    "[DoctorPet] 이메일 인증을 완료해주세요",
                    "아래 링크를 클릭하면 이메일 인증이 완료됩니다(24시간 이내 유효, 1회용):\n" + link
            );
        } catch (RuntimeException exception) {
            log.error("이메일 인증 메일 발송 실패: memberId={}", member.getId(), exception);
        }
    }

    @Transactional
    public void verifyEmail(String token) {
        Long memberId = memberTokenRepository.consumeEmailVerificationToken(token)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.verifyEmail();
    }

    /*
      재발송. 계정 존재 여부를 노출하지 않기 위해(PasswordResetService.requestPasswordReset()과
      동일한 정책) 회원이 없거나 이미 인증된 경우에도 예외 없이 조용히 반환한다 — 응답만으로는
      "이 이메일로 가입된 계정이 있는지"·"이미 인증됐는지"를 알 수 없다.
     */
    public void resendVerificationEmail(String email) {
        memberRepository.findByEmail(email)
                .filter(member -> !member.isEmailVerified())
                .ifPresent(this::sendVerificationEmail);
    }
}
