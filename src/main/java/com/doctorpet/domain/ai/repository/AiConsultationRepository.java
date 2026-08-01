package com.doctorpet.domain.ai.repository;

import com.doctorpet.domain.ai.entity.AiConsultation;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiConsultationRepository extends JpaRepository<AiConsultation, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AiConsultation consultation
            set consultation.symptomText = null
            where consultation.createdAt < :cutoff
              and consultation.symptomText is not null
            """)
    int clearSymptomTextsCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
