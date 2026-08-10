package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HospitalOperatingScheduleRepository
        extends JpaRepository<HospitalOperatingSchedule, Long> {
}
