package com.doctorpet.domain.reservation.policy;

import java.time.Duration;

public final class ReservationPolicy {

    public static final Duration LEAD_TIME = Duration.ofHours(4);

    private ReservationPolicy() {
    }
}
