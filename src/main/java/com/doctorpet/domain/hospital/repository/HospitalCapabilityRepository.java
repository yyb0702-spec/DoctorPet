package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 제휴 병원이 보유한 진료 역량을 저장하고 조회합니다.
 */
public interface HospitalCapabilityRepository
        extends JpaRepository<HospitalCapability, Long> {

    List<HospitalCapability> findAllByHospital(Hospital hospital);

    List<HospitalCapability> findAllByHospitalId(Long hospitalId);
}
