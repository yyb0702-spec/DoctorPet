package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 제휴 병원의 운영·시설 상세정보를 저장하고 조회합니다.
 */
public interface HospitalDetailRepository
        extends JpaRepository<HospitalDetail, Long> {

    Optional<HospitalDetail> findByHospital(Hospital hospital);

    @Query("""
            SELECT detail
            FROM HospitalDetail detail
            JOIN FETCH detail.hospital hospital
            WHERE hospital.partnershipStatus = com.doctorpet.domain.hospital.entity.PartnershipStatus.PARTNER
              AND NOT EXISTS (
                  SELECT schedule.id
                  FROM HospitalOperatingSchedule schedule
                  WHERE schedule.hospital = hospital
                    AND schedule.effectiveFrom <= :effectiveDate
              )
            ORDER BY hospital.id ASC
            """)
    List<HospitalDetail> findPartnerDetailsWithoutEffectiveSchedule(
            @Param("effectiveDate") LocalDate effectiveDate
    );
}
