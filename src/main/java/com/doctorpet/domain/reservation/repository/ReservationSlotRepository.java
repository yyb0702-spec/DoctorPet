package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSlotRepository extends JpaRepository<ReservationSlot, Long> {

}
