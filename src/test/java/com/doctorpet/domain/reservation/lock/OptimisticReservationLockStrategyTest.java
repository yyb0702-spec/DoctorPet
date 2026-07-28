package com.doctorpet.domain.reservation.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class OptimisticReservationLockStrategyTest {

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    @InjectMocks
    private OptimisticReservationLockStrategy lockStrategy;

    @Test
    @DisplayName("낙관적 락 충돌을 ALREADY_RESERVED 도메인 오류로 변환한다")
    void reserve_optimisticLockConflict_throwsAlreadyReserved() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1);
        ReservationSlot slot = ReservationSlot.create(
                1L,
                startAt,
                startAt.plusMinutes(30)
        );
        given(reservationSlotRepository.findById(1L)).willReturn(Optional.of(slot));
        doThrow(new ObjectOptimisticLockingFailureException(
                ReservationSlot.class,
                1L
        )).when(reservationSlotRepository).flush();

        assertThatThrownBy(() -> lockStrategy.reserve(1L))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(SlotErrorCode.ALREADY_RESERVED));
    }
}
