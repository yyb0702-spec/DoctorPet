package com.doctorpet.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationTest {

    @Test
    @DisplayName("REQUESTED 예약을 취소하면 CANCELED와 취소 시각이 기록된다")
    void cancel_requestedReservation_changesToCanceled() {
        Reservation reservation = createReservation();
        LocalDateTime canceledAt = LocalDateTime.now();

        reservation.cancel(canceledAt);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(reservation.getCanceledAt()).isEqualTo(canceledAt);
    }

    @Test
    @DisplayName("CONFIRMED 예약도 취소할 수 있다")
    void cancel_confirmedReservation_changesToCanceled() {
        Reservation reservation = createReservation();
        reservation.confirm(LocalDateTime.now());

        reservation.cancel(LocalDateTime.now());

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELED);
    }

    @Test
    @DisplayName("CHECKED_IN 예약은 취소할 수 없다")
    void cancel_checkedInReservation_throwsInvalidStatus() {
        Reservation reservation = createReservation();
        reservation.confirm(LocalDateTime.now());
        reservation.checkIn();

        assertThatThrownBy(() -> reservation.cancel(LocalDateTime.now()))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(ReservationErrorCode.INVALID_STATUS));
    }

    private Reservation createReservation() {
        return Reservation.request(
                1L,
                2L,
                3L,
                4L,
                5L,
                "초코",
                "DOG",
                LocalDateTime.now()
        );
    }
}
