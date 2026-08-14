package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationWaitlistErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationWaitlistServiceTest {

    private static final long MEMBER_ID = 1L;
    private static final long SLOT_ID = 2L;

    @Mock
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    @Mock
    private ReservationApplicationService reservationApplicationService;

    @Mock
    private ReservationSlotReleaseService reservationSlotReleaseService;

    private ReservationWaitlistService reservationWaitlistService;

    @BeforeEach
    void setUp() {
        reservationWaitlistService = new ReservationWaitlistService(
                reservationWaitlistRepository,
                reservationSlotRepository,
                reservationApplicationService,
                reservationSlotReleaseService,
                Clock.fixed(
                        LocalDateTime.of(2026, 8, 13, 10, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        ZoneId.of("Asia/Seoul")
                )
        );
    }

    @Test
    @DisplayName("보호자는 RESERVED 슬롯에 WAITING 상태로 대기열을 등록할 수 있다")
    void register_reservedSlot_savesWaiting() {
        ReservationSlot slot = reservedSlot();
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));
        given(reservationWaitlistRepository.existsByMemberIdAndSlotId(MEMBER_ID, SLOT_ID))
                .willReturn(false);
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        ReflectionTestUtils.setField(waitlist, "id", 10L);
        given(reservationWaitlistRepository.insertWaitingIfSlotReserved(MEMBER_ID, SLOT_ID)).willReturn(1);
        given(reservationWaitlistRepository.findByMemberIdAndSlotId(MEMBER_ID, SLOT_ID))
                .willReturn(Optional.of(waitlist));

        var result = reservationWaitlistService.register(MEMBER_ID, SLOT_ID);

        assertThat(result.waitlistId()).isEqualTo(10L);
        assertThat(result.slotId()).isEqualTo(SLOT_ID);
        assertThat(result.status()).isEqualTo(ReservationWaitlistStatus.WAITING);
        verify(reservationWaitlistRepository).insertWaitingIfSlotReserved(MEMBER_ID, SLOT_ID);
    }

    @Test
    @DisplayName("OPEN 슬롯에는 대기열을 등록할 수 없다")
    void register_openSlot_throwsSlotNotReserved() {
        ReservationSlot openSlot = ReservationSlot.create(
                3L,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusMinutes(30)
        );
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(openSlot));

        assertThatThrownBy(() -> reservationWaitlistService.register(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationWaitlistErrorCode.SLOT_NOT_RESERVED);

        verify(reservationWaitlistRepository, never()).insertWaitingIfSlotReserved(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 슬롯에는 대기열을 등록할 수 없다")
    void register_missingSlot_throwsSlotNotFound() {
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationWaitlistService.register(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(SlotErrorCode.SLOT_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 보호자는 같은 슬롯 대기열에 두 번 등록할 수 없다")
    void register_duplicate_throwsAlreadyRegistered() {
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(reservedSlot()));
        given(reservationWaitlistRepository.existsByMemberIdAndSlotId(MEMBER_ID, SLOT_ID))
                .willReturn(true);

        assertThatThrownBy(() -> reservationWaitlistService.register(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationWaitlistErrorCode.ALREADY_REGISTERED);
    }

    @Test
    @DisplayName("동시 등록에서 UNIQUE 제약에 진 요청은 중복 등록으로 처리한다")
    void register_duplicateKeyRace_throwsAlreadyRegistered() {
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(reservedSlot()));
        given(reservationWaitlistRepository.existsByMemberIdAndSlotId(MEMBER_ID, SLOT_ID))
                .willReturn(false);
        given(reservationWaitlistRepository.insertWaitingIfSlotReserved(MEMBER_ID, SLOT_ID)).willReturn(0);
        given(reservationWaitlistRepository.existsByMemberIdAndSlotId(MEMBER_ID, SLOT_ID))
                .willReturn(false);

        assertThatThrownBy(() -> reservationWaitlistService.register(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationWaitlistErrorCode.SLOT_NOT_RESERVED);
    }

    @Test
    @DisplayName("OFFERED 대기자가 수락하면 REQUESTED 예약을 만들고 ACCEPTED로 전이한다")
    void accept_activeOffer_createsRequestedReservation() {
        ReservationWaitlist waitlist = offeredWaitlist(10L);
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(reservedSlot()));
        given(reservationWaitlistRepository.acceptIfActive(10L, MEMBER_ID,
                LocalDateTime.of(2026, 8, 13, 10, 0))).willReturn(1);
        ReservationResponse response = new ReservationResponse(
                100L, 3L, 4L, SLOT_ID, ReservationStatus.REQUESTED, LocalDateTime.now());
        given(reservationApplicationService.requestFromWaitlist(MEMBER_ID, 3L, 4L, SLOT_ID))
                .willReturn(response);

        ReservationResponse result = reservationWaitlistService.accept(MEMBER_ID, 10L, 3L, 4L);

        assertThat(result.status()).isEqualTo(ReservationStatus.REQUESTED);
        verify(reservationWaitlistRepository).acceptIfActive(
                10L, MEMBER_ID, LocalDateTime.of(2026, 8, 13, 10, 0));
    }

    @Test
    @DisplayName("수락 조건부 UPDATE가 0건이면 REQUESTED 예약을 만들지 않는다")
    void accept_conditionallyRejected_doesNotCreateReservation() {
        ReservationWaitlist waitlist = offeredWaitlist(10L);
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(reservedSlot()));
        given(reservationWaitlistRepository.acceptIfActive(10L, MEMBER_ID,
                LocalDateTime.of(2026, 8, 13, 10, 0))).willReturn(0);

        assertThatThrownBy(() -> reservationWaitlistService.accept(MEMBER_ID, 10L, 3L, 4L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationWaitlistErrorCode.OFFER_NOT_ACTIVE);
        verify(reservationApplicationService, never()).requestFromWaitlist(MEMBER_ID, 3L, 4L, SLOT_ID);
    }

    @Test
    @DisplayName("예약 시작 4시간 이내에는 대기열 등록과 수락을 허용하지 않는다")
    void leadTimeViolation_blocksRegisterAndAccept() {
        ReservationSlot slot = ReservationSlot.create(
                3L,
                LocalDateTime.of(2026, 8, 13, 13, 59),
                LocalDateTime.of(2026, 8, 13, 14, 29));
        slot.reserve();
        given(reservationSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));

        assertThatThrownBy(() -> reservationWaitlistService.register(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(com.doctorpet.domain.reservation.exception.ReservationErrorCode.LEAD_TIME_VIOLATION);

        ReservationWaitlist waitlist = offeredWaitlist(10L);
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));
        assertThatThrownBy(() -> reservationWaitlistService.accept(MEMBER_ID, 10L, 3L, 4L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(com.doctorpet.domain.reservation.exception.ReservationErrorCode.LEAD_TIME_VIOLATION);
    }

    @Test
    @DisplayName("OFFERED 대기자의 거절은 다음 대기자 승급을 위해 슬롯 반환을 호출한다")
    void reject_activeOffer_releasesSlotForNextCandidate() {
        ReservationWaitlist waitlist = offeredWaitlist(10L);
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));

        reservationWaitlistService.reject(MEMBER_ID, 10L);

        assertThat(waitlist.getStatus()).isEqualTo(ReservationWaitlistStatus.REJECTED);
        verify(reservationWaitlistRepository).flush();
        verify(reservationSlotReleaseService).release(SLOT_ID);
    }

    @Test
    @DisplayName("만료된 OFFERED 대기자는 EXPIRED로 전이하고 다음 대기자 처리를 시작한다")
    void expire_expiredOffer_releasesSlotForNextCandidate() {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        LocalDateTime offeredAt = LocalDateTime.of(2026, 8, 13, 9, 0);
        waitlist.offer(offeredAt, offeredAt.plusMinutes(10));
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));

        boolean expired = reservationWaitlistService.expire(
                10L,
                LocalDateTime.of(2026, 8, 13, 9, 10)
        );

        assertThat(expired).isTrue();
        assertThat(waitlist.getStatus()).isEqualTo(ReservationWaitlistStatus.EXPIRED);
        verify(reservationSlotReleaseService).release(SLOT_ID);
    }

    @Test
    @DisplayName("보호자는 자신의 대기열 목록과 상세만 조회할 수 있다")
    void getMyWaitlistsAndDetail_returnsOnlyOwnedWaitlists() {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        ReflectionTestUtils.setField(waitlist, "id", 10L);
        given(reservationWaitlistRepository.findAllByMemberIdOrderByCreatedAtDescIdDesc(MEMBER_ID))
                .willReturn(List.of(waitlist));
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));

        var list = reservationWaitlistService.getMyWaitlists(MEMBER_ID);
        var detail = reservationWaitlistService.getMyWaitlist(MEMBER_ID, 10L);

        assertThat(list).hasSize(1);
        assertThat(detail.waitlistId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("다른 보호자는 대기열 상세를 조회하거나 취소할 수 없다")
    void getOrCancel_otherMembersWaitlist_throwsForbidden() {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waitlist));

        assertThatThrownBy(() -> reservationWaitlistService.getMyWaitlist(2L, 10L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(com.doctorpet.global.exception.CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> reservationWaitlistService.cancel(2L, 10L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(com.doctorpet.global.exception.CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("보호자는 WAITING 대기열만 취소할 수 있고 OFFERED 취소는 차단된다")
    void cancel_onlyWaitingIsAllowed() {
        ReservationWaitlist waiting = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        given(reservationWaitlistRepository.findById(10L)).willReturn(Optional.of(waiting));

        reservationWaitlistService.cancel(MEMBER_ID, 10L);

        assertThat(waiting.getStatus()).isEqualTo(ReservationWaitlistStatus.CANCELED);
        ReservationWaitlist offered = offeredWaitlist(11L);
        given(reservationWaitlistRepository.findById(11L)).willReturn(Optional.of(offered));
        assertThatThrownBy(() -> reservationWaitlistService.cancel(MEMBER_ID, 11L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationWaitlistErrorCode.CANCELLATION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("회원 탈퇴 시 WAITING은 취소하고 OFFERED는 슬롯 반환까지 처리한다")
    void cancelAllForWithdrawal_cancelsWaitingAndReleasesOfferedSlot() {
        ReservationWaitlist waiting = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        ReservationWaitlist offered = offeredWaitlist(11L);
        given(reservationWaitlistRepository.findByMemberIdAndStatusIn(
                MEMBER_ID,
                List.of(ReservationWaitlistStatus.WAITING, ReservationWaitlistStatus.OFFERED)))
                .willReturn(List.of(waiting, offered));

        reservationWaitlistService.cancelAllForWithdrawal(MEMBER_ID);

        assertThat(waiting.getStatus()).isEqualTo(ReservationWaitlistStatus.CANCELED);
        assertThat(offered.getStatus()).isEqualTo(ReservationWaitlistStatus.CANCELED);
        verify(reservationSlotReleaseService).release(SLOT_ID);
    }

    private ReservationSlot reservedSlot() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1);
        ReservationSlot slot = ReservationSlot.create(3L, startAt, startAt.plusMinutes(30));
        slot.reserve();
        return slot;
    }

    private ReservationWaitlist offeredWaitlist(Long waitlistId) {
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(MEMBER_ID, SLOT_ID);
        ReflectionTestUtils.setField(waitlist, "id", waitlistId);
        LocalDateTime now = LocalDateTime.of(2026, 8, 13, 10, 0);
        waitlist.offer(now.minusMinutes(1), now.plusMinutes(9));
        return waitlist;
    }
}
