package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ReservationCancellationIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdSlotId;

    @AfterEach
    void cleanUp() {
        if (createdSlotId != null) {
            jdbcTemplate.update(
                    "delete from reservations where slot_id = ?",
                    createdSlotId
            );
            jdbcTemplate.update(
                    "delete from reservation_slots where id = ?",
                    createdSlotId
            );
        }
    }

    @Test
    @DisplayName("취소 성공 시 예약은 CANCELED, 슬롯은 OPEN으로 함께 변경된다")
    void cancel_success_changesReservationAndSlot() {
        TestReservation data = saveReservation(
                LocalDateTime.now().plusDays(2),
                ReservationStatus.REQUESTED,
                true
        );

        reservationService.cancel(data.memberId(), data.reservationId());

        assertReservationStatus(data.reservationId(), ReservationStatus.CANCELED);
        assertSlotStatus(data.slotId(), ReservationSlotStatus.OPEN);
    }

    @Test
    @DisplayName("취소 기한이 지나면 예약과 슬롯 상태가 모두 유지된다")
    void cancel_afterDeadline_keepsBothStates() {
        TestReservation data = saveReservation(
                LocalDateTime.now().plusHours(1),
                ReservationStatus.REQUESTED,
                true
        );

        assertThatThrownBy(() ->
                reservationService.cancel(data.memberId(), data.reservationId())
        )
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(ReservationErrorCode.CANCEL_DEADLINE_PASSED));

        assertReservationStatus(data.reservationId(), ReservationStatus.REQUESTED);
        assertSlotStatus(data.slotId(), ReservationSlotStatus.RESERVED);
    }

    @Test
    @DisplayName("취소할 수 없는 예약 상태면 예약과 슬롯 상태가 모두 유지된다")
    void cancel_invalidReservationStatus_keepsBothStates() {
        TestReservation data = saveReservation(
                LocalDateTime.now().plusDays(2),
                ReservationStatus.CHECKED_IN,
                true
        );

        assertThatThrownBy(() ->
                reservationService.cancel(data.memberId(), data.reservationId())
        )
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(ReservationErrorCode.INVALID_STATUS));

        assertReservationStatus(data.reservationId(), ReservationStatus.CHECKED_IN);
        assertSlotStatus(data.slotId(), ReservationSlotStatus.RESERVED);
    }

    @Test
    @DisplayName("슬롯 반환이 실패하면 예약 취소도 롤백된다")
    void cancel_slotReleaseFails_rollsBackReservationCancel() {
        TestReservation data = saveReservation(
                LocalDateTime.now().plusDays(2),
                ReservationStatus.REQUESTED,
                false
        );

        assertThatThrownBy(() ->
                reservationService.cancel(data.memberId(), data.reservationId())
        )
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(SlotErrorCode.INVALID_STATUS));

        assertReservationStatus(data.reservationId(), ReservationStatus.REQUESTED);
        assertSlotStatus(data.slotId(), ReservationSlotStatus.OPEN);
    }

    private TestReservation saveReservation(
            LocalDateTime startAt,
            ReservationStatus targetStatus,
            boolean reserveSlot
    ) {
        long memberId = System.nanoTime();
        long hospitalId = memberId + 1;
        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                startAt.withNano(0),
                startAt.withNano(0).plusMinutes(30)
        );
        if (reserveSlot) {
            slot.reserve();
        }
        slot = reservationSlotRepository.saveAndFlush(slot);
        createdSlotId = slot.getId();

        Reservation reservation = Reservation.request(
                memberId,
                1L,
                hospitalId,
                slot.getId(),
                1L,
                "초코",
                "DOG",
                LocalDateTime.now()
        );
        moveToStatus(reservation, targetStatus);
        reservation = reservationRepository.saveAndFlush(reservation);

        return new TestReservation(memberId, reservation.getId(), slot.getId());
    }

    private void moveToStatus(
            Reservation reservation,
            ReservationStatus targetStatus
    ) {
        if (targetStatus == ReservationStatus.CONFIRMED
                || targetStatus == ReservationStatus.CHECKED_IN) {
            reservation.confirm(LocalDateTime.now());
        }
        if (targetStatus == ReservationStatus.CHECKED_IN) {
            reservation.checkIn();
        }
    }

    private void assertReservationStatus(
            Long reservationId,
            ReservationStatus expectedStatus
    ) {
        Reservation reloaded = reservationRepository.findById(reservationId)
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(expectedStatus);
    }

    private void assertSlotStatus(
            Long slotId,
            ReservationSlotStatus expectedStatus
    ) {
        ReservationSlot reloaded = reservationSlotRepository.findById(slotId)
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(expectedStatus);
    }

    private record TestReservation(
            Long memberId,
            Long reservationId,
            Long slotId
    ) {
    }
}
