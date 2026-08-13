package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationWaitlistPromotionServiceTest {

    private static final long SLOT_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 10, 0);

    @Mock
    private ReservationWaitlistRepository reservationWaitlistRepository;

    private ReservationWaitlistPromotionService promotionService;

    @BeforeEach
    void setUp() {
        ReservationWaitlistProperties properties = new ReservationWaitlistProperties();
        properties.setOfferValidityMinutes(10);
        Clock fixedClock = Clock.fixed(
                NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                ZoneId.of("Asia/Seoul")
        );
        promotionService = new ReservationWaitlistPromotionService(
                reservationWaitlistRepository,
                properties,
                fixedClock
        );
    }

    @Test
    @DisplayName("FIFO 첫 WAITING 대기자 한 명에게만 10분 승급 제안을 만든다")
    void offerFirstWaiting_offersOnlyFifoFirst() {
        ReservationWaitlist first = ReservationWaitlist.waiting(1L, SLOT_ID);
        ReservationWaitlist second = ReservationWaitlist.waiting(2L, SLOT_ID);
        given(reservationWaitlistRepository.findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                SLOT_ID,
                ReservationWaitlistStatus.WAITING
        )).willReturn(List.of(first, second));

        ReservationWaitlist offered = promotionService.offerFirstWaiting(SLOT_ID).orElseThrow();

        assertThat(offered).isSameAs(first);
        assertThat(first.getStatus()).isEqualTo(ReservationWaitlistStatus.OFFERED);
        assertThat(first.getOfferedAt()).isEqualTo(NOW);
        assertThat(first.getOfferExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(second.getStatus()).isEqualTo(ReservationWaitlistStatus.WAITING);
    }

    @Test
    @DisplayName("WAITING 대기자가 없으면 승급 제안을 만들지 않는다")
    void offerFirstWaiting_noCandidate_returnsEmpty() {
        given(reservationWaitlistRepository.findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                SLOT_ID,
                ReservationWaitlistStatus.WAITING
        )).willReturn(List.of());

        assertThat(promotionService.offerFirstWaiting(SLOT_ID)).isEmpty();
    }

    @Test
    @DisplayName("이미 OFFERED인 대기자는 다시 승급 제안할 수 없다")
    void offer_rejectsNonWaitingState() {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(1L, SLOT_ID);
        waitlist.offer(NOW, NOW.plusMinutes(10));

        assertThatThrownBy(() -> waitlist.offer(NOW.plusMinutes(1), NOW.plusMinutes(11)))
                .isInstanceOf(IllegalStateException.class);
    }
}
