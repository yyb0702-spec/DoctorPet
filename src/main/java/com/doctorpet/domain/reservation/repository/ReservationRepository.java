package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional <Reservation> findByIdAndMemberId(
            Long reservationId,
            Long memberId
    );

    long countBySlotId(Long slotId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :canceledStatus,
                   r.canceledAt = :canceledAt
             where r.id = :reservationId
               and r.memberId = :memberId
               and r.status in (:requestedStatus, :confirmedStatus)
            """)
    int cancelIfAllowed(
            @Param("reservationId") Long reservationId,
            @Param("memberId") Long memberId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("canceledStatus") ReservationStatus canceledStatus,
            @Param("canceledAt") LocalDateTime canceledAt
    );
}
