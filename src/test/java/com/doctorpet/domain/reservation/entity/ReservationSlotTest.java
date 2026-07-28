package com.doctorpet.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationSlotTest {

    @Test
    @DisplayName("OPEN 슬롯을 점유하면 RESERVED로 변경된다")
    void reserve_openSlot_changesToReserved() {
        ReservationSlot slot = createSlot();

        slot.reserve();

        assertThat(slot.getStatus()).isEqualTo(ReservationSlotStatus.RESERVED);
    }

    @Test
    @DisplayName("이미 점유된 슬롯을 다시 점유하면 ALREADY_RESERVED 예외가 발생한다")
    void reserve_reservedSlot_throwsAlreadyReserved() {
        ReservationSlot slot = createSlot();
        slot.reserve();

        assertThatThrownBy(slot::reserve)
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(SlotErrorCode.ALREADY_RESERVED));
    }

    @Test
    @DisplayName("RESERVED 슬롯을 반환하면 OPEN으로 변경된다")
    void open_reservedSlot_changesToOpen() {
        ReservationSlot slot = createSlot();
        slot.reserve();

        slot.open();

        assertThat(slot.getStatus()).isEqualTo(ReservationSlotStatus.OPEN);
    }

    @Test
    @DisplayName("OPEN 슬롯을 반환하려 하면 INVALID_STATUS 예외가 발생한다")
    void open_openSlot_throwsInvalidStatus() {
        ReservationSlot slot = createSlot();

        assertThatThrownBy(slot::open)
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(SlotErrorCode.INVALID_STATUS));
    }

    private ReservationSlot createSlot() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1);
        return ReservationSlot.create(1L, startAt, startAt.plusMinutes(30));
    }
}
