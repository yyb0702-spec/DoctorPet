package com.doctorpet.domain.reservation.lock;

import com.doctorpet.domain.reservation.entity.ReservationSlot;

public interface ReservationLockStrategy {

    ReservationSlot reserve(Long slotId);
}
