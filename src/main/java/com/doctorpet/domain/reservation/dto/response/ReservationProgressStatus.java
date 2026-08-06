package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;

public enum ReservationProgressStatus {
    RESERVATION_REQUESTED,
    RESERVATION_CONFIRMED,
    CHECKED_IN,
    IN_TREATMENT,
    TREATMENT_COMPLETED,
    PAYMENT_COMPLETED,
    RESERVATION_REJECTED,
    RESERVATION_CANCELED,
    NO_SHOW_PENDING,
    NO_SHOW;

    public static ReservationProgressStatus from(
            ReservationStatus reservationStatus,
            String paymentStatus
    ) {
        if (reservationStatus == ReservationStatus.TREATMENT_COMPLETED
                && ("PAID".equals(paymentStatus)
                || "OFFLINE_PAID".equals(paymentStatus))) {
            return PAYMENT_COMPLETED;
        }

        return switch (reservationStatus) {
            case REQUESTED -> RESERVATION_REQUESTED;
            case CONFIRMED -> RESERVATION_CONFIRMED;
            case CHECKED_IN -> CHECKED_IN;
            case IN_TREATMENT -> IN_TREATMENT;
            case TREATMENT_COMPLETED -> TREATMENT_COMPLETED;
            case REJECTED -> RESERVATION_REJECTED;
            case CANCELED -> RESERVATION_CANCELED;
            case NO_SHOW_PENDING -> NO_SHOW_PENDING;
            case NO_SHOW -> NO_SHOW;
        };
    }
}
