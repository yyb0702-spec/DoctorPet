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
    @DisplayName("REQUESTED 예약을 승인하면 CONFIRMED와 승인 시각이 기록된다")
    void confirm_requestedReservation_changesToConfirmed() {
        Reservation reservation = createReservation();
        LocalDateTime confirmedAt = LocalDateTime.now();

        reservation.confirm(confirmedAt);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isEqualTo(confirmedAt);
    }

    @Test
    @DisplayName("REQUESTED가 아닌 예약은 승인할 수 없다")
    void confirm_nonRequestedReservation_throwsInvalidStatus() {
        Reservation reservation = createReservation();
        reservation.confirm(LocalDateTime.now());

        assertInvalidStatus(() -> reservation.confirm(LocalDateTime.now()));
    }

    @Test
    @DisplayName("REQUESTED 예약을 거절하면 REJECTED와 사유가 기록된다")
    void reject_requestedReservation_changesToRejected() {
        Reservation reservation = createReservation();

        reservation.reject("진료 불가");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(reservation.getRejectReason()).isEqualTo("진료 불가");
    }

    @Test
    @DisplayName("거절 사유가 비어 있으면 예약을 거절할 수 없다")
    void reject_blankReason_throwsReasonRequired() {
        Reservation reservation = createReservation();

        assertThatThrownBy(() -> reservation.reject(" "))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(ReservationErrorCode.REJECT_REASON_REQUIRED));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REQUESTED);
    }

    @Test
    @DisplayName("CONFIRMED 예약을 체크인하면 CHECKED_IN으로 변경된다")
    void checkIn_confirmedReservation_changesToCheckedIn() {
        Reservation reservation = confirmedReservation();

        reservation.checkIn();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
    }

    @Test
    @DisplayName("체크인 예약은 진료중을 거쳐 진료완료로 변경된다")
    void treatment_checkedInReservation_completesTreatment() {
        Reservation reservation = confirmedReservation();
        reservation.checkIn();

        reservation.startTreatment();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.IN_TREATMENT);

        reservation.completeTreatment();
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.TREATMENT_COMPLETED);
    }

    @Test
    @DisplayName("CHECKED_IN이 아닌 예약은 진료를 시작할 수 없다")
    void startTreatment_nonCheckedInReservation_throwsInvalidStatus() {
        assertInvalidStatus(() -> createReservation().startTreatment());
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
        reservation.confirm(LocalDateTime.now());
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
