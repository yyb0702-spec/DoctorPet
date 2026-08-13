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
import com.doctorpet.domain.reservation.exception.ReservationWaitlistErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
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

    private ReservationWaitlistService reservationWaitlistService;

    @BeforeEach
    void setUp() {
        reservationWaitlistService = new ReservationWaitlistService(
                reservationWaitlistRepository,
                reservationSlotRepository
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
        given(reservationWaitlistRepository.insertWaitingIfAbsent(MEMBER_ID, SLOT_ID)).willReturn(1);
        given(reservationWaitlistRepository.findByMemberIdAndSlotId(MEMBER_ID, SLOT_ID))
                .willReturn(Optional.of(waitlist));

        var result = reservationWaitlistService.register(MEMBER_ID, SLOT_ID);

        assertThat(result.waitlistId()).isEqualTo(10L);
        assertThat(result.slotId()).isEqualTo(SLOT_ID);
        assertThat(result.status()).isEqualTo(ReservationWaitlistStatus.WAITING);
        verify(reservationWaitlistRepository).insertWaitingIfAbsent(MEMBER_ID, SLOT_ID);
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

        verify(reservationWaitlistRepository, never()).insertWaitingIfAbsent(any(), any());
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
        given(reservationWaitlistRepository.insertWaitingIfAbsent(MEMBER_ID, SLOT_ID)).willReturn(0);

        assertThatThrownBy(() -> reservationWaitlistService.register(MEMBER_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationWaitlistErrorCode.ALREADY_REGISTERED);
    }

    private ReservationSlot reservedSlot() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1);
        ReservationSlot slot = ReservationSlot.create(3L, startAt, startAt.plusMinutes(30));
        slot.reserve();
        return slot;
    }
}
