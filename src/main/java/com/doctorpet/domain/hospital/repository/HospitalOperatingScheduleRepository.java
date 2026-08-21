package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HospitalOperatingScheduleRepository
        extends JpaRepository<HospitalOperatingSchedule, Long> {

    @Query("""
            SELECT schedule
            FROM HospitalOperatingSchedule schedule
            WHERE schedule.hospital.id = :hospitalId
              AND schedule.effectiveFrom = (
                  SELECT MAX(candidate.effectiveFrom)
                  FROM HospitalOperatingSchedule candidate
                  WHERE candidate.hospital.id = :hospitalId
                    AND candidate.effectiveFrom <= :today
              )
            """)
    Optional<HospitalOperatingSchedule> findEffectiveSchedule(
            @Param("hospitalId") Long hospitalId,
            @Param("today") LocalDate today
    );

    @Query("""
            SELECT schedule
            FROM HospitalOperatingSchedule schedule
            WHERE schedule.hospital.id = :hospitalId
              AND schedule.effectiveFrom = :effectiveFrom
            """)
    Optional<HospitalOperatingSchedule> findSchedule(
            @Param("hospitalId") Long hospitalId,
            @Param("effectiveFrom") LocalDate effectiveFrom
    );

    @Query("""
            SELECT schedule
            FROM HospitalOperatingSchedule schedule
            WHERE schedule.hospital.id = :hospitalId
              AND schedule.effectiveFrom > :today
            ORDER BY schedule.effectiveFrom ASC
            """)
    List<HospitalOperatingSchedule> findScheduledSchedules(
            @Param("hospitalId") Long hospitalId,
            @Param("today") LocalDate today
    );

    @Query("""
            SELECT DISTINCT schedule.hospital.id
            FROM HospitalOperatingSchedule schedule
            WHERE schedule.effectiveFrom <= :businessDate
              AND schedule.hospital.partnershipStatus = com.doctorpet.domain.hospital.entity.PartnershipStatus.PARTNER
              AND schedule.hospital.businessStatus = com.doctorpet.domain.hospital.entity.BusinessStatus.OPEN
            ORDER BY schedule.hospital.id ASC
            """)
    List<Long> findHospitalIdsWithEffectiveSchedule(
            @Param("businessDate") LocalDate businessDate
    );
}
