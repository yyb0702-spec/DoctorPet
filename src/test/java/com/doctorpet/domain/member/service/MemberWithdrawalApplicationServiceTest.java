package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 회원 탈퇴는 Member 도메인 하나로 끝나지 않는다(SA §6-3, 부록A 확정 — 탈퇴 보류 정책) — 이
 * 클래스는 ReservationService 조회 결과에 따라 실제 탈퇴 처리를 막거나 진행시키는 오케스트레이션만
 * 검증한다. 미수금(OFFLINE_REQUIRED) 체크는 결제(청구) 도메인이 아직 없어 여기 포함되지 않았다.
 */
@ExtendWith(MockitoExtension.class)
class MemberWithdrawalApplicationServiceTest {

    @Mock
    private MemberService memberService;

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private MemberWithdrawalApplicationService memberWithdrawalApplicationService;

    @Test
    @DisplayName("활성 예약이 없으면 MemberService.withdraw()를 호출해 탈퇴를 진행한다")
    void withdraw_noActiveReservation_proceeds() {
        given(reservationService.hasActiveReservation(1L)).willReturn(false);

        memberWithdrawalApplicationService.withdraw(1L);

        then(memberService).should().withdraw(1L);
    }

    @Test
    @DisplayName("활성 예약이 있으면 WITHDRAWAL_BLOCKED을 던지고 MemberService.withdraw()는 호출하지 않는다")
    void withdraw_hasActiveReservation_blocked() {
        given(reservationService.hasActiveReservation(1L)).willReturn(true);

        assertThatThrownBy(() -> memberWithdrawalApplicationService.withdraw(1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.WITHDRAWAL_BLOCKED));
        then(memberService).should(never()).withdraw(1L);
    }
}
