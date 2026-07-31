package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationSlotRepository extends JpaRepository<ReservationSlot, Long> {

    @Query("""
            SELECT slot
            FROM ReservationSlot slot
            WHERE slot.hospitalId = :hospitalId
              AND slot.startAt >= :rangeStart
              AND slot.startAt < :rangeEnd
            ORDER BY slot.startAt ASC, slot.id ASC
            """)
    List<ReservationSlot> findSlotsInRange(
            @Param("hospitalId") Long hospitalId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd
    );
}
