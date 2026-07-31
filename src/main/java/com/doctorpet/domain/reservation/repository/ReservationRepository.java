package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long>, ReservationQueryRepository {

    Optional <Reservation> findByIdAndMemberId(
            Long reservationId,
            Long memberId
    );

    Optional<Reservation> findByIdAndHospitalId(
            Long reservationId,
            Long hospitalId
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

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :confirmedStatus,
                   r.confirmedAt = :confirmedAt,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :requestedStatus
            """)
    int approveIfRequested(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("confirmedAt") LocalDateTime confirmedAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :rejectedStatus,
                   r.rejectReason = :rejectReason,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :requestedStatus
            """)
    int rejectIfRequested(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("rejectedStatus") ReservationStatus rejectedStatus,
            @Param("rejectReason") String rejectReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :checkedInStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :confirmedStatus
            """)
    int checkInIfConfirmed(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("checkedInStatus") ReservationStatus checkedInStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :inTreatmentStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :checkedInStatus
            """)
    int startTreatmentIfCheckedIn(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("checkedInStatus") ReservationStatus checkedInStatus,
            @Param("inTreatmentStatus") ReservationStatus inTreatmentStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :completedStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :inTreatmentStatus
            """)
    int completeTreatmentIfInTreatment(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("inTreatmentStatus") ReservationStatus inTreatmentStatus,
            @Param("completedStatus") ReservationStatus completedStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
