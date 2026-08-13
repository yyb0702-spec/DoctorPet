package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.LocalDateTime;
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
        ReservationSlot slot = reservedSlot();
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));
        given(promotionService.offerFirstWaiting(SLOT_ID))
                .willReturn(Optional.of(ReservationWaitlist.waiting(1L, SLOT_ID)));

        releaseService.release(SLOT_ID);

        assertThat(slot.getStatus()).isEqualTo(ReservationSlotStatus.RESERVED);
        verify(promotionService).offerFirstWaiting(SLOT_ID);
    }

    @Test
    @DisplayName("WAITING 대기자가 없을 때만 슬롯을 OPEN으로 반환한다")
    void release_withoutWaitingCandidate_opensSlot() {
        ReservationSlot slot = reservedSlot();
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));
        given(promotionService.offerFirstWaiting(SLOT_ID)).willReturn(Optional.empty());

        releaseService.release(SLOT_ID);

        assertThat(slot.getStatus()).isEqualTo(ReservationSlotStatus.OPEN);
    }

    private ReservationSlot reservedSlot() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1);
        ReservationSlot slot = ReservationSlot.create(3L, startAt, startAt.plusMinutes(30));
        slot.reserve();
        return slot;
    }
}
