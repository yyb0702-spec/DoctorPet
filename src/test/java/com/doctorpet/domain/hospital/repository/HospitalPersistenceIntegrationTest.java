package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 병원 스키마의 JSON 매핑과 DB 제약을 실제 MySQL에서 검증합니다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import(QuerydslConfig.class)
class HospitalPersistenceIntegrationTest {

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalDetailRepository hospitalDetailRepository;

    @Autowired
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 운영시간_JSON을_저장하고_다시_조회한다() {
        Hospital hospital = hospitalRepository.saveAndFlush(
                createHospital("JSON-ROUND-TRIP")
        );
        HospitalDetail detail = HospitalDetail.create(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(20, 0)
                        ),
                        DayOfWeek.SATURDAY,
                        new DailyOperatingHours(
                                LocalTime.of(10, 0),
                                LocalTime.of(17, 0)
                        )
                ),
                true,
                true,
                false,
                false
        );
        hospitalDetailRepository.saveAndFlush(detail);

        // 1차 캐시가 아닌 MySQL JSON 값을 다시 읽도록 영속성 컨텍스트를 비웁니다.
        entityManager.clear();
        Hospital reloadedHospital = hospitalRepository
                .findById(hospital.getId())
                .orElseThrow();
        HospitalDetail reloadedDetail = hospitalDetailRepository
                .findByHospital(reloadedHospital)
                .orElseThrow();

        assertThat(reloadedDetail.getOpenHours())
                .containsEntry(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(20, 0)
                        )
                )
                .containsEntry(
                        DayOfWeek.SATURDAY,
                        new DailyOperatingHours(
                                LocalTime.of(10, 0),
                                LocalTime.of(17, 0)
                        )
                );
    }

    @Test
    void 동일한_지자체코드와_관리번호의_병원을_중복_저장할_수_없다() {
        hospitalRepository.saveAndFlush(
                createHospital("DUPLICATE-HOSPITAL")
        );

        assertThatThrownBy(() -> hospitalRepository.saveAndFlush(
                createHospital("DUPLICATE-HOSPITAL")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_병원에_상세정보를_두_건_저장할_수_없다() {
        Hospital hospital = hospitalRepository.saveAndFlush(
                createHospital("DUPLICATE-DETAIL")
        );
        hospitalDetailRepository.saveAndFlush(createDetail(hospital));

        assertThatThrownBy(() -> hospitalDetailRepository.saveAndFlush(
                createDetail(hospital)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_병원에_같은_역량을_중복_저장할_수_없다() {
        Hospital hospital = hospitalRepository.saveAndFlush(
                createHospital("DUPLICATE-CAPABILITY")
        );
        hospitalCapabilityRepository.saveAndFlush(
                HospitalCapability.create(
                        hospital,
                        CapabilityValue.XRAY
                )
        );

        assertThatThrownBy(() ->
                hospitalCapabilityRepository.saveAndFlush(
                        HospitalCapability.create(
                                hospital,
                                CapabilityValue.XRAY
                        )
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_병원으로_역량을_저장할_수_없다() {
        assertThatThrownBy(() -> entityManager.createNativeQuery("""
                        INSERT INTO hospital_capabilities (
                            hospital_id,
                            capability_type,
                            capability_value
                        ) VALUES (
                            999999999,
                            'EXAM',
                            'XRAY'
                        )
                        """)
                .executeUpdate()
        ).isInstanceOfAny(
                PersistenceException.class,
                DataIntegrityViolationException.class
        );
    }

    private HospitalDetail createDetail(Hospital hospital) {
        return HospitalDetail.create(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(18, 0)
                        )
                ),
                true,
                true,
                false,
                false
        );
    }

    private Hospital createHospital(String managementNumber) {
        return Hospital.createFromPublicData(
                managementNumber,
                "TEST-LOCAL-GOV",
                "통합 테스트 동물병원",
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
