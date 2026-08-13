package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 제휴 병원이 보유한 진료 역량을 저장하고 조회합니다.
 */
public interface HospitalCapabilityRepository
        extends JpaRepository<HospitalCapability, Long> {

    List<HospitalCapability> findAllByHospital(Hospital hospital);

    List<HospitalCapability> findAllByHospitalId(Long hospitalId);

    int countByHospitalId(Long hospitalId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM HospitalCapability capability "
            + "WHERE capability.hospital.id = :hospitalId")
    int deleteAllByHospitalId(@Param("hospitalId") Long hospitalId);
}
