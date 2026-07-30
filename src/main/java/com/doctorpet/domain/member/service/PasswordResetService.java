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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정(백로그 P2). 이메일 링크형 토큰 방식 — 로그인 상태가 아니어도(비밀번호를 잊은
 * 상태이므로 애초에 로그인할 수 없다) 이메일 소유만 확인되면 새 비밀번호로 교체할 수 있다.
 * SA "재설정 성공 시 잠금 해제" 효과는 Member.resetPassword()가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofHours(1);

    private final MemberRepository memberRepository;
    private final MemberTokenRepository memberTokenRepository;
    private final EmailGateway emailGateway;
    private final PasswordEncoder passwordEncoder;

    @Value("${mail.password-reset.base-url}")
    private String passwordResetBaseUrl;

    /*
      재설정 요청. 계정 존재 여부를 노출하지 않기 위해(MEMBER_002 INVALID_CREDENTIALS와 같은 취지)
      이메일이 가입돼 있지 않아도 예외 없이 조용히 반환한다 — 응답만 보고는 가입 여부를 알 수 없다.
     */
    public void requestPasswordReset(String email) {
        memberRepository.findByEmail(email).ifPresent(member -> {
            String token = memberTokenRepository.issuePasswordResetToken(member.getId(), TOKEN_TTL);
            String link = passwordResetBaseUrl + "?token=" + token;
            try {
                emailGateway.send(
                        member.getEmail(),
                        "[DoctorPet] 비밀번호 재설정",
                        "아래 링크에서 비밀번호를 재설정하세요(1시간 이내 유효, 1회용):\n" + link
                );
            } catch (RuntimeException exception) {
                log.error("비밀번호 재설정 메일 발송 실패: memberId={}", member.getId(), exception);
            }
        });
    }

    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        Long memberId = memberTokenRepository.consumePasswordResetToken(token)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.resetPassword(passwordEncoder.encode(newPassword));
    }
}
