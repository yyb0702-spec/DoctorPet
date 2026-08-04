package com.doctorpet.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationTest {

    @Test
    @DisplayName("예약을 요청하면 REQUESTED 상태와 요청 시각이 기록된다")
    void request_createsRequestedReservation() {
        LocalDateTime requestedAt = LocalDateTime.now();

        Reservation reservation = createReservation(requestedAt);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REQUESTED);
        assertThat(reservation.getRequestedAt()).isEqualTo(requestedAt);
        assertThat(reservation.getPetNameSnapshot()).isEqualTo("초코");
        assertThat(reservation.getPetSpeciesSnapshot()).isEqualTo("DOG");
    }

    @Test
    @DisplayName("CONFIRMED 예약을 노쇼 처리하면 NO_SHOW와 처리 시각이 기록된다")
    void markNoShow_confirmedReservation_changesToNoShow() {
        Reservation reservation = confirmedReservation();
        LocalDateTime noShowAt = LocalDateTime.now();

        reservation.markNoShow(noShowAt);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getNoShowAt()).isEqualTo(noShowAt);
    }

    @Test
    @DisplayName("NO_SHOW 예약을 정정하면 CHECKED_IN으로 복구된다")
    void restoreNoShow_noShowReservation_changesToCheckedIn() {
        Reservation reservation = confirmedReservation();
        reservation.markNoShow(LocalDateTime.now());

        reservation.restoreNoShow();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("NO_SHOW가 아닌 예약은 노쇼 정정할 수 없다")
    void restoreNoShow_nonNoShowReservation_throwsInvalidStatus() {
        assertInvalidStatus(() -> createReservation().restoreNoShow());
    }

    private Reservation createReservation() {
        return createReservation(LocalDateTime.now());
    }

    private Reservation createReservation(LocalDateTime requestedAt) {
        return Reservation.request(
                1L,
                2L,
                3L,
                4L,
                5L,
                "초코",
                "DOG",
                requestedAt
        );
    }

    private Reservation confirmedReservation() {
        Reservation reservation = createReservation();
        ReflectionTestUtils.setField(
                reservation,
                "status",
                ReservationStatus.CONFIRMED
        );
        ReflectionTestUtils.setField(
                reservation,
                "confirmedAt",
                LocalDateTime.now()
        );
        return reservation;
    }

    private void assertInvalidStatus(Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(ReservationErrorCode.INVALID_STATUS));
    }
}
