package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationWaitlistPromotionServiceTest {

    private static final long SLOT_ID = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 10, 0);

    @Mock
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    @Mock
    private MemberService memberService;

    @Mock
    private ReservationNotificationPublisher notificationPublisher;

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
                reservationSlotRepository,
                memberService,
                notificationPublisher,
                properties,
                fixedClock
        );
    }

    @Test
    @DisplayName("FIFO 첫 WAITING 대기자 한 명에게만 10분 승급 제안을 만든다")
    void offerFirstWaiting_offersOnlyFifoFirst() {
        ReservationWaitlist first = ReservationWaitlist.waiting(1L, SLOT_ID);
        ReflectionTestUtils.setField(first, "id", 11L);
        ReservationWaitlist second = ReservationWaitlist.waiting(2L, SLOT_ID);
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(java.util.Optional.of(reservableSlot()));
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
        verify(notificationPublisher).publishWaitlistOffered(1L, 11L, NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("WAITING 대기자가 없으면 승급 제안을 만들지 않는다")
    void offerFirstWaiting_noCandidate_returnsEmpty() {
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(java.util.Optional.of(reservableSlot()));
        given(reservationWaitlistRepository.findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                SLOT_ID,
                ReservationWaitlistStatus.WAITING
        )).willReturn(List.of());

        assertThat(promotionService.offerFirstWaiting(SLOT_ID)).isEmpty();
        verify(notificationPublisher, never()).publishWaitlistOffered(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("예약 시작 4시간 이내에는 대기열 승급 제안을 만들지 않는다")
    void offerFirstWaiting_insideLeadTime_returnsEmpty() {
        ReservationSlot slot = ReservationSlot.create(
                3L, NOW.plusHours(3).plusMinutes(59), NOW.plusHours(4).plusMinutes(29));
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(java.util.Optional.of(slot));

        assertThat(promotionService.offerFirstWaiting(SLOT_ID)).isEmpty();
        verify(notificationPublisher, never()).publishWaitlistOffered(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("4시간 경계 전 10분보다 짧은 제안은 리드타임 마감 시각에 만료된다")
    void offerFirstWaiting_nearLeadTime_capsExpirationAtLatestAcceptTime() {
        ReservationSlot slot = ReservationSlot.create(
                3L, NOW.plusHours(4).plusMinutes(5), NOW.plusHours(4).plusMinutes(35));
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(1L, SLOT_ID);
        ReflectionTestUtils.setField(waitlist, "id", 11L);
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(java.util.Optional.of(slot));
        given(reservationWaitlistRepository.findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                SLOT_ID, ReservationWaitlistStatus.WAITING)).willReturn(List.of(waitlist));

        ReservationWaitlist offered = promotionService.offerFirstWaiting(SLOT_ID).orElseThrow();

        assertThat(offered.getOfferExpiresAt()).isEqualTo(NOW.plusMinutes(5));
        verify(notificationPublisher).publishWaitlistOffered(1L, 11L, NOW.plusMinutes(5));
    }

    @Test
    @DisplayName("정확히 4시간 전에는 수락 가능한 시간이 없어 승급 제안을 만들지 않는다")
    void offerFirstWaiting_atLeadTimeBoundary_returnsEmpty() {
        ReservationSlot slot = ReservationSlot.create(
                3L, NOW.plusHours(4), NOW.plusHours(4).plusMinutes(30));
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(java.util.Optional.of(slot));

        assertThat(promotionService.offerFirstWaiting(SLOT_ID)).isEmpty();
    }

    @Test
    @DisplayName("탈퇴한 FIFO 첫 대기자는 CANCELED로 건너뛰고 다음 활성 보호자에게 제안한다")
    void offerFirstWaiting_skipsWithdrawnMember() {
        ReservationWaitlist withdrawn = ReservationWaitlist.waiting(1L, SLOT_ID);
        ReservationWaitlist active = ReservationWaitlist.waiting(2L, SLOT_ID);
        ReflectionTestUtils.setField(active, "id", 12L);
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(java.util.Optional.of(reservableSlot()));
        given(reservationWaitlistRepository.findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                SLOT_ID, ReservationWaitlistStatus.WAITING)).willReturn(List.of(withdrawn, active));
        org.mockito.Mockito.doThrow(new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND))
                .when(memberService).assertActiveMember(1L);

        ReservationWaitlist offered = promotionService.offerFirstWaiting(SLOT_ID).orElseThrow();

        assertThat(withdrawn.getStatus()).isEqualTo(ReservationWaitlistStatus.CANCELED);
        assertThat(offered).isSameAs(active);
        verify(notificationPublisher).publishWaitlistOffered(2L, 12L, NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("이미 OFFERED인 대기자는 다시 승급 제안할 수 없다")
    void offer_rejectsNonWaitingState() {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(1L, SLOT_ID);
        waitlist.offer(NOW, NOW.plusMinutes(10));

        assertThatThrownBy(() -> waitlist.offer(NOW.plusMinutes(1), NOW.plusMinutes(11)))
                .isInstanceOf(IllegalStateException.class);
    }

    private ReservationSlot reservableSlot() {
        return ReservationSlot.create(3L, NOW.plusHours(5), NOW.plusHours(5).plusMinutes(30));
    }
}
