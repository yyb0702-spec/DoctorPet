package com.doctorpet.domain.reservation.entity.status;

public enum ReservationStatus {
    REQUESTED,
    CONFIRMED,
    REJECTED,
    CANCELED,
    HOSPITAL_CANCELED,
    NO_SHOW_PENDING,
    CHECKED_IN,
    IN_TREATMENT,
    TREATMENT_COMPLETED,
    NO_SHOW
}
