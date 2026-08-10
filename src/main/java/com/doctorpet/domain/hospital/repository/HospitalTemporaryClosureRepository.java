package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HospitalTemporaryClosureRepository
        extends JpaRepository<HospitalTemporaryClosure, Long> {
}
