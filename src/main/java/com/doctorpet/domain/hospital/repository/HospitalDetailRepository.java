package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 제휴 병원의 운영·시설 상세정보를 저장하고 조회합니다.
 */
public interface HospitalDetailRepository
        extends JpaRepository<HospitalDetail, Long> {

    Optional<HospitalDetail> findByHospital(Hospital hospital);
}
