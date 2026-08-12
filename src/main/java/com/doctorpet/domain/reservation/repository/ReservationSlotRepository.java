package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("""
            SELECT slot
            FROM ReservationSlot slot
            WHERE slot.hospitalId = :hospitalId
              AND slot.businessDate = :businessDate
            ORDER BY slot.startAt ASC, slot.id ASC
            """)
    List<ReservationSlot> findBusinessDateSlots(
            @Param("hospitalId") Long hospitalId,
            @Param("businessDate") LocalDate businessDate
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT slot
            FROM ReservationSlot slot
            WHERE slot.hospitalId = :hospitalId
              AND slot.businessDate = :businessDate
            ORDER BY slot.startAt ASC, slot.id ASC
            """)
    List<ReservationSlot> findBusinessDateSlotsForUpdate(
            @Param("hospitalId") Long hospitalId,
            @Param("businessDate") LocalDate businessDate
    );

    @Query("""
            SELECT MAX(slot.businessDate)
            FROM ReservationSlot slot
            WHERE slot.hospitalId = :hospitalId
              AND slot.businessDate >= :fromDate
              AND slot.businessDate <= :toDate
              AND slot.startAt >= :rangeStart
              AND slot.startAt < :rangeEnd
              AND slot.status = com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus.RESERVED
            """)
    Optional<LocalDate> findLatestReservedBusinessDate(
            @Param("hospitalId") Long hospitalId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT slot
            FROM ReservationSlot slot
            WHERE slot.hospitalId = :hospitalId
              AND slot.businessDate >= :fromDate
              AND slot.businessDate <= :toDate
              AND slot.startAt >= :rangeStart
              AND slot.startAt < :rangeEnd
            ORDER BY slot.startAt ASC, slot.id ASC
            """)
    List<ReservationSlot> findBusinessDateSlotsInRangeForUpdate(
            @Param("hospitalId") Long hospitalId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM ReservationSlot slot
            WHERE slot.hospitalId = :hospitalId
              AND slot.businessDate = :businessDate
              AND slot.status = com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus.OPEN
            """)
    int deleteOpenSlots(
            @Param("hospitalId") Long hospitalId,
            @Param("businessDate") LocalDate businessDate
    );
}
