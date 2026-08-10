package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Optional;

public interface HospitalTemporaryClosureRepository
        extends JpaRepository<HospitalTemporaryClosure, Long> {

    @Query("""
            SELECT closure
            FROM HospitalTemporaryClosure closure
            WHERE closure.hospital.id = :hospitalId
              AND closure.businessDate = :businessDate
            """)
    Optional<HospitalTemporaryClosure> findClosure(
            @Param("hospitalId") Long hospitalId,
            @Param("businessDate") LocalDate businessDate
    );
}
