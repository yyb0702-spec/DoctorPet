package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.MemberTokenRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
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
    private final RefreshTokenRepository refreshTokenRepository;
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
            // 토큰 발급(Redis)까지 try 안에 포함한다 — Redis 장애로 토큰 발급 자체가 실패해도
            // 이 메서드는 항상 조용히 반환해야 하는 계약(계정 존재 여부 비노출)을 지켜야 한다.
            try {
                String token = memberTokenRepository.issuePasswordResetToken(member.getId(), TOKEN_TTL);
                String link = passwordResetBaseUrl + "?token=" + token;
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

    /*
     * findByIdForUpdate(비관적 락, 리뷰 지적): login()의 findByEmailForUpdate()와 같은 행을 잠가
     * 재설정과 로그인 두 흐름을 직렬화한다. 잠금 없이 findById()로 읽으면, 이 트랜잭션이 비밀번호를
     * 아직 커밋하지 않은 사이 공격자가 옛 비밀번호로 로그인을 통과해 새 Refresh Token을 저장할 수
     * 있고, 그 직후 이 트랜잭션이 커밋돼 비밀번호는 바뀌어도 그 Refresh Token은 이미 저장된 뒤라
     * 아래 deleteByMemberId()가 지우지 못한다 — 세션 무효화라는 이번 수정의 목적 자체가 무력화된다.
     * 같은 행 락을 공유하면 어느 쪽이 먼저 잡았든 나머지는 앞선 트랜잭션이 커밋할 때까지 대기하므로,
     * 재설정이 먼저면 뒤이은 로그인은 이미 바뀐 비밀번호로 검증돼 실패하고, 로그인이 먼저면 재설정은
     * 로그인이 끝난 뒤 커밋되면서 로그인이 방금 저장한 Refresh Token까지 함께 삭제한다.
     */
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        Long memberId = memberTokenRepository.consumePasswordResetToken(token)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.INVALID_OR_EXPIRED_TOKEN));

        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.resetPassword(passwordEncoder.encode(newPassword));

        // 계정 탈취 복구 시나리오 대응(기능 구멍 점검) — 비밀번호 재설정 성공은 "공격자가 세션을
        // 쥐고 있을 수 있다"는 전제로 다뤄야 한다. Refresh Token을 지우지 않으면 공격자가 가진
        // 세션이 새 비밀번호와 무관하게 재발급을 통해 계속 연장된다. MemberWithdrawalApplicationService의
        // 탈퇴 처리와 동일한 패턴(RefreshTokenRepository.deleteByMemberId)이며, 그때와 같은
        // 한계도 동일하게 남는다 — 무상태 JWT라 이미 발급된 Access Token은 자연 만료 전까지는
        // 서명 검증만으로 계속 유효하다(즉시 강제 폐기 불가).
        refreshTokenRepository.deleteByMemberId(memberId);
    }
}
