package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // 병원 행 잠금 전에 만들어진 REPEATABLE_READ 스냅샷을 재사용하지 않도록 현재 읽기로 가져온다.
    // CREATE의 동일 발효일 재확인과 UPDATE의 stale token 검증 모두 이 조회 결과만 사용한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT schedule
            FROM HospitalOperatingSchedule schedule
            WHERE schedule.hospital.id = :hospitalId
              AND schedule.effectiveFrom = :effectiveFrom
            """)
    Optional<HospitalOperatingSchedule> findScheduleForUpdate(
            @Param("hospitalId") Long hospitalId,
            @Param("effectiveFrom") LocalDate effectiveFrom
    );

    /*
      UPDATE 직렬화의 펜스다. 병원 행 잠금이 요청의 순서를 정해도 @LastModifiedDate는 고정 Clock
      테스트나 매우 촘촘한 요청에서 같은 값으로 남을 수 있다. 저장된 토큰을 조건으로 한 UPDATE가
      정확히 1행일 때만 다음 토큰을 부여해, 뒤늦은 요청이 같은 토큰을 다시 통과하지 못하게 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE HospitalOperatingSchedule schedule
               SET schedule.updatedAt = :nextUpdatedAt
             WHERE schedule.id = :scheduleId
               AND schedule.updatedAt = :currentUpdatedAt
            """)
    int advanceUpdateToken(
            @Param("scheduleId") Long scheduleId,
            @Param("currentUpdatedAt") java.time.LocalDateTime currentUpdatedAt,
            @Param("nextUpdatedAt") java.time.LocalDateTime nextUpdatedAt
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
