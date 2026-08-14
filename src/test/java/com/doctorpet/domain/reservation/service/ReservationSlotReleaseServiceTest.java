package com.doctorpet.domain.reservation.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationSlotReleaseServiceTest {

    private static final long SLOT_ID = 10L;

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    @Mock
    private ReservationWaitlistPromotionService promotionService;

    private ReservationSlotReleaseService releaseService;

    @BeforeEach
    void setUp() {
        releaseService = new ReservationSlotReleaseService(
                reservationSlotRepository,
                promotionService
        );
    }

    @Test
    @DisplayName("FIFO 대기자가 있으면 슬롯을 RESERVED로 유지한다")
    void release_withWaitingCandidate_keepsSlotReserved() {
        given(promotionService.offerFirstWaiting(SLOT_ID))
                .willReturn(Optional.of(ReservationWaitlist.waiting(1L, SLOT_ID)));

        releaseService.release(SLOT_ID);

        verify(promotionService).offerFirstWaiting(SLOT_ID);
    }

    @Test
    @DisplayName("WAITING 대기자가 없을 때만 슬롯을 OPEN으로 반환한다")
    void release_withoutWaitingCandidate_opensSlot() {
        given(promotionService.offerFirstWaiting(SLOT_ID)).willReturn(Optional.empty());
        given(reservationSlotRepository.openIfNoWaitingWaitlist(SLOT_ID)).willReturn(1);

        releaseService.release(SLOT_ID);

        verify(reservationSlotRepository).openIfNoWaitingWaitlist(SLOT_ID);
    }

    @Test
    @DisplayName("슬롯 OPEN 직전 등록된 WAITING 대기자는 다시 승급을 시도한다")
    void release_waitlistRegisteredDuringOpenCheck_retriesPromotion() {
        given(promotionService.offerFirstWaiting(SLOT_ID)).willReturn(
                Optional.empty(), Optional.of(ReservationWaitlist.waiting(1L, SLOT_ID)));
        given(reservationSlotRepository.openIfNoWaitingWaitlist(SLOT_ID)).willReturn(0);

        releaseService.release(SLOT_ID);

        verify(promotionService, org.mockito.Mockito.times(2)).offerFirstWaiting(SLOT_ID);
    }
}
