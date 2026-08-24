package com.doctorpet.domain.hospital.partnership.service;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalDetailSeedData;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalOperatingHoursSeedData;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.mapper.PartnerHospitalSeedMapper;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.global.config.QuerydslConfig;
import com.doctorpet.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 제휴 시드 한 건이 실패하면 앞서 처리한 변경까지 실제 MySQL에서 롤백되는지 검증합니다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        PartnerHospitalSeedService.class,
        PartnerHospitalSeedMapper.class,
        JpaAuditingConfig.class,
        QuerydslConfig.class
})
class PartnerHospitalSeedTransactionIntegrationTest {

    @Autowired
    private PartnerHospitalSeedService seedService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalDetailRepository hospitalDetailRepository;

    @Autowired
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Autowired
    private HospitalOperatingScheduleRepository operatingScheduleRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 한_건이라도_매칭에_실패하면_제휴_시드_전체를_취소한다() {
        Hospital hospital = hospitalRepository.save(
                createHospital("ROLLBACK-PARTNER-SEED")
        );

        try {
            PartnerHospitalSeedData validSeed =
                    createSeed("ROLLBACK-PARTNER-SEED", "정상 제휴 병원");
            PartnerHospitalSeedData missingSeed =
                    createSeed("MISSING-PARTNER-SEED", "누락 제휴 병원");

            assertThatThrownBy(() -> seedService.applyPartnerships(
                    List.of(validSeed, missingSeed)
            )).isInstanceOf(IllegalStateException.class);

            // 첫 번째 병원 처리도 함께 롤백되어 제휴 상태와 부가 데이터가 남지 않아야 합니다.
            Hospital reloaded = hospitalRepository.findById(hospital.getId())
                    .orElseThrow();
            assertThat(reloaded.getPartnershipStatus())
                    .isEqualTo(PartnershipStatus.NON_PARTNER);
            assertThat(hospitalDetailRepository.findByHospital(reloaded))
                    .isEmpty();
            assertThat(hospitalCapabilityRepository.findAllByHospital(reloaded))
                    .isEmpty();
            assertThat(operatingScheduleRepository
                    .findEffectiveSchedule(reloaded.getId(), java.time.LocalDate.now()))
                    .isEmpty();
        } finally {
            hospitalRepository.deleteById(hospital.getId());
        }
    }

    @Test
    @Transactional
    void 기존_제휴_병원에_현재_적용할_운영_스케줄이_없으면_상세_운영시간으로_초기화한다() {
        Hospital hospital = hospitalRepository.save(
                createHospital("INITIAL-OPERATING-SCHEDULE")
        );
        hospital.markAsPartner();
        hospitalRepository.saveAndFlush(hospital);
        hospitalDetailRepository.saveAndFlush(HospitalDetail.create(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new com.doctorpet.domain.hospital.model.DailyOperatingHours(
                                java.time.LocalTime.of(20, 0),
                                java.time.LocalTime.of(2, 0)
                        )
                ),
                true,
                false,
                true,
                false
        ));
        java.time.LocalDate effectiveDate = java.time.LocalDate.of(2026, 8, 11);

        try {
            int initialized = seedService.initializeMissingOperatingSchedules(effectiveDate);

            assertThat(initialized).isPositive();
            var schedule = operatingScheduleRepository
                    .findEffectiveSchedule(hospital.getId(), effectiveDate)
                    .orElseThrow();
            assertThat(schedule.getEffectiveFrom()).isEqualTo(effectiveDate);
            assertThat(schedule.getOperatingHours().get(DayOfWeek.MONDAY))
                    .containsExactly(new com.doctorpet.domain.hospital.model.DailyOperatingHours(
                            java.time.LocalTime.of(20, 0),
                            java.time.LocalTime.of(2, 0)
                    ));
            assertThat(seedService.initializeMissingOperatingSchedules(effectiveDate))
                    .isZero();
        } finally {
            operatingScheduleRepository
                    .findSchedule(hospital.getId(), effectiveDate)
                    .ifPresent(operatingScheduleRepository::delete);
            hospitalDetailRepository.findByHospital(hospital)
                    .ifPresent(hospitalDetailRepository::delete);
            hospitalRepository.deleteById(hospital.getId());
        }
    }

    private PartnerHospitalSeedData createSeed(
            String managementNumber,
            String hospitalName
    ) {
        return new PartnerHospitalSeedData(
                "TEST-LOCAL-GOV",
                managementNumber,
                hospitalName,
                new PartnerHospitalDetailSeedData(
                        Map.of(
                                DayOfWeek.MONDAY,
                                new PartnerHospitalOperatingHoursSeedData(
                                        "09:00",
                                        "18:00"
                                )
                        ),
                        true,
                        true,
                        false,
                        false
                ),
                List.of(CapabilityValue.DOG, CapabilityValue.XRAY)
        );
    }

    private Hospital createHospital(String managementNumber) {
        return Hospital.createFromPublicData(
                managementNumber,
                "TEST-LOCAL-GOV",
                "트랜잭션 테스트 동물병원",
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
