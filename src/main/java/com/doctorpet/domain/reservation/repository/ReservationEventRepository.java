package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationEvent;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationEventRepository extends JpaRepository<ReservationEvent, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert ignore into reservation_events
                (reservation_id, event_type, memo, processed_by, occurred_at)
            values
                (:reservationId, :eventType, :memo, :processedBy, :occurredAt)
            """, nativeQuery = true)
    int appendIfAbsent(
            @Param("reservationId") Long reservationId,
            @Param("eventType") String eventType,
            @Param("memo") String memo,
            @Param("processedBy") Long processedBy,
            @Param("occurredAt") LocalDateTime occurredAt
    );

    List<ReservationEvent> findAllByReservation_IdOrderByOccurredAtAsc(Long reservationId);

    long countByReservation_IdAndEventType(
            Long reservationId,
            ReservationEventType eventType
    );
}
