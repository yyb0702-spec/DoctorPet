package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.domain.hospital.service.HospitalFavoriteService;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.domain.reservation.service.ReservationWaitlistService;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  회원 탈퇴는 Member 도메인 하나로 끝나지 않는다 — 활성 예약(CONFIRMED·CHECKED_IN) 또는
  미수금(OFFLINE_REQUIRED) 보유 회원은 탈퇴를 보류한다(SA §6-3, 부록A 확정). 여러 도메인을
  조합하는 흐름이라 구현 가드레일에 따라 XxxApplicationService로 분리한다 — Member가
  ReservationRepository/PaymentRepository를 직접 참조하지 않고 각 도메인 Service를 경유한다.

  주의 — 미수금(OFFLINE_REQUIRED) 체크는 아직 여기 없다. 이 시점 기준으로 결제(청구/Payment)
  도메인 자체가 아직 구현되지 않아(payment 패키지엔 PaymentMethod만 있고 Payment/PaymentStatus가
  없다) 실제로 검증할 대상이 존재하지 않는다. Payment 도메인이 추가되면 그 Service의 조회
  메서드를 여기에 같은 방식으로 추가해야 한다 — 그 전까지는 활성 예약 체크만으로 정책의
  절반을 이미 강제한다(둘 다 없어야 탈퇴 가능하다는 정책 중, 예약 쪽은 지금도 완전하다).

  또 다른 주의 — 탈퇴 직후에도 이미 발급된 Access Token은 만료 전까지 서명·만료 자체는 유효하다
  (무상태 JWT라 즉시 강제 폐기가 원천적으로 불가능한 건 여전하다). Refresh Token 삭제는 그
  유효기간이 "재발급으로 연장되는 것"만 막을 뿐이라, 남은 Access Token으로 다른 도메인의 쓰기
  API(예: 예약 생성, 결제수단 등록)를 호출하는 경로까지는 막지 못한다는 리뷰 지적(P1)이 있었다.
  이를 막기 위해 RefreshTokenRepository.blacklistMember()로 탈퇴 사실을 Redis에 기록하고,
  JwtAuthenticationFilter가 Access Token 인증 직전에 이 블랙리스트를 확인한다 — 특정 도메인의
  쓰기 경로 하나하나에 개별 방어(assertActiveMember() 등)를 추가하는 대신, 인증 계층(A 도메인
  소유) 한 곳에서 막아 놓치는 경로가 생길 위험을 없앤다. 블랙리스트 TTL은 Access Token 만료
  시간과 정확히 맞춰, 그 이후엔 어차피 자연 만료라 별도 정리 없이 항목이 자동으로 사라진다.
 */
@Service
@RequiredArgsConstructor
public class MemberWithdrawalApplicationService {

    private final MemberService memberService;
    private final ReservationService reservationService;
    private final ReservationWaitlistService reservationWaitlistService;
    private final HospitalFavoriteService hospitalFavoriteService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;

    @Transactional
    public void withdraw(Long memberId) {
        memberService.lockActiveMember(memberId);

        if (reservationService.hasActiveReservation(memberId)) {
            throw new ServiceException(MemberErrorCode.WITHDRAWAL_BLOCKED);
        }

        reservationWaitlistService.cancelAllForWithdrawal(memberId);
        hospitalFavoriteService.deleteAllByMemberId(memberId);
        memberService.withdraw(memberId);
        refreshTokenRepository.deleteByMemberId(memberId);
        refreshTokenRepository.blacklistMember(
                memberId,
                Duration.ofMillis(jwtProperties.getAccessTokenExpiration())
        );
    }
}
