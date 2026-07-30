package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.lock.ReservationLockStrategy;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * hasActiveReservation()은 회원 탈퇴(Member 도메인)가 이 Service를 경유해서 호출하는
 * 조회 메서드다(SA §6-3, 부록A 확정). Reservation 도메인 자체 관점에서는 CONFIRMED·CHECKED_IN
 * 상태만 "활성"으로 취급하는지만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    @Mock
    private ReservationLockStrategy reservationLockStrategy;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("CONFIRMED 또는 CHECKED_IN 예약이 있으면 true를 반환한다")
    void hasActiveReservation_true() {
        given(reservationRepository.existsByMemberIdAndStatusIn(
                eq(1L), eq(activeStatuses()))).willReturn(true);

        boolean result = reservationService.hasActiveReservation(1L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("CONFIRMED·CHECKED_IN 예약이 없으면 false를 반환한다(REQUESTED·CANCELED 등은 활성으로 안 본다)")
    void hasActiveReservation_false() {
        given(reservationRepository.existsByMemberIdAndStatusIn(
                eq(1L), eq(activeStatuses()))).willReturn(false);

        boolean result = reservationService.hasActiveReservation(1L);

        assertThat(result).isFalse();
    }

    private Collection<ReservationStatus> activeStatuses() {
        return java.util.List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);
    }
}
