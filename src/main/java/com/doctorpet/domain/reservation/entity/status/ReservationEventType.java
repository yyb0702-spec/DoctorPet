package com.doctorpet.domain.reservation.entity.status;

public enum ReservationEventType {
    REQUESTED,
    APPROVED,
    REJECTED,
    CANCELED,
    CHECKED_IN,
    TREATMENT_STARTED,
    TREATMENT_COMPLETED,
    NO_SHOWED,
    NO_SHOW_RESTORED
}
