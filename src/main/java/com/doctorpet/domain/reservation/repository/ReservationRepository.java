package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional <Reservation> findByIdAndMemberId(
            Long reservationId,
            Long memberId
    );
}
