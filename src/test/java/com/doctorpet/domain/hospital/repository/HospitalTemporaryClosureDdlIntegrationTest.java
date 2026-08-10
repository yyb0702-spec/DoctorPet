package com.doctorpet.domain.hospital.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class HospitalTemporaryClosureDdlIntegrationTest {

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalTemporaryClosureRepository closureRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void temporaryClosurePersistsByBusinessDate() {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital("CLOSURE-PERSIST"));
        LocalDate businessDate = LocalDate.of(2026, 9, 20);

        HospitalTemporaryClosure saved = closureRepository.saveAndFlush(
                HospitalTemporaryClosure.create(hospital, businessDate)
        );
        entityManager.clear();

        assertThat(closureRepository.findById(saved.getId()))
                .get()
                .extracting(HospitalTemporaryClosure::getBusinessDate)
                .isEqualTo(businessDate);
    }

    @Test
    void sameHospitalAndBusinessDateCannotBeDuplicated() {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital("CLOSURE-UNIQUE"));
        LocalDate businessDate = LocalDate.of(2026, 9, 20);
        closureRepository.saveAndFlush(HospitalTemporaryClosure.create(hospital, businessDate));

        assertThatThrownBy(() -> closureRepository.saveAndFlush(
                HospitalTemporaryClosure.create(hospital, businessDate)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void temporaryClosureRequiresExistingHospital() {
        assertThatThrownBy(() -> entityManager.createNativeQuery("""
                        INSERT INTO hospital_temporary_closures (
                            hospital_id, business_date, created_at, updated_at
                        ) VALUES (
                            999999999, '2026-09-20', NOW(6), NOW(6)
                        )
                        """).executeUpdate())
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    private Hospital createHospital(String managementNumber) {
        return Hospital.createFromPublicData(
                managementNumber,
                "TEST-LOCAL-GOV",
                "테스트 동물병원",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
    }
}
