package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public interface ReservationQueryRepository {

    Page<Reservation> findMyReservations(
            Long memberId,
            ReservationStatus status,
            LocalDateTime fromAt,
            LocalDateTime toExclusive,
            Sort.Direction direction,
            Pageable pageable
    );
}
