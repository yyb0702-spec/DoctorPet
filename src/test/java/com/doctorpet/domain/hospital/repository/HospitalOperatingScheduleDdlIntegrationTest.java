package com.doctorpet.domain.hospital.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class HospitalOperatingScheduleDdlIntegrationTest {

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void multipleOperatingPeriodsArePersistedAsJson() {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital("SCHEDULE-JSON"));
        HospitalOperatingSchedule saved = scheduleRepository.saveAndFlush(
                HospitalOperatingSchedule.create(
                        hospital,
                        LocalDate.of(2026, 8, 11),
                        Map.of(
                                DayOfWeek.MONDAY,
                                List.of(
                                        period(9, 0, 12, 0),
                                        period(13, 0, 18, 0)
                                ),
                                DayOfWeek.TUESDAY,
                                List.of()
                        )
                )
        );
        entityManager.clear();

        HospitalOperatingSchedule reloaded = scheduleRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(reloaded.getOperatingHours().get(DayOfWeek.MONDAY)).containsExactly(
                period(9, 0, 12, 0),
                period(13, 0, 18, 0)
        );
        assertThat(reloaded.getOperatingHours().get(DayOfWeek.TUESDAY)).isEmpty();
        assertThat(reloaded.getOperatingHours()).hasSize(7);
    }

    @Test
    void sameHospitalAndEffectiveFromCannotBeDuplicated() {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital("SCHEDULE-UNIQUE"));
        LocalDate effectiveFrom = LocalDate.of(2026, 8, 11);
        scheduleRepository.saveAndFlush(schedule(hospital, effectiveFrom));

        assertThatThrownBy(() -> scheduleRepository.saveAndFlush(schedule(hospital, effectiveFrom)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void scheduleRequiresExistingHospital() {
        assertThatThrownBy(() -> entityManager.createNativeQuery("""
                        INSERT INTO hospital_operating_schedules (
                            hospital_id, effective_from, operating_hours, created_at, updated_at
                        ) VALUES (
                            999999999, '2026-08-11', JSON_OBJECT(), NOW(6), NOW(6)
                        )
                        """).executeUpdate())
                .isInstanceOfAny(PersistenceException.class, DataIntegrityViolationException.class);
    }

    @Test
    void effectiveScheduleUsesLatestDateNotAfterToday() {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital("SCHEDULE-EFFECTIVE"));
        scheduleRepository.saveAndFlush(schedule(hospital, LocalDate.of(2026, 8, 1)));
        HospitalOperatingSchedule current = scheduleRepository.saveAndFlush(
                schedule(hospital, LocalDate.of(2026, 8, 10))
        );
        scheduleRepository.saveAndFlush(schedule(hospital, LocalDate.of(2026, 8, 12)));
        entityManager.clear();

        assertThat(scheduleRepository.findEffectiveSchedule(
                hospital.getId(),
                LocalDate.of(2026, 8, 11)
        )).get().extracting(HospitalOperatingSchedule::getId).isEqualTo(current.getId());
    }

    private HospitalOperatingSchedule schedule(Hospital hospital, LocalDate effectiveFrom) {
        return HospitalOperatingSchedule.create(
                hospital,
                effectiveFrom,
                Map.of(DayOfWeek.MONDAY, List.of(period(9, 0, 18, 0)))
        );
    }

    private DailyOperatingHours period(int startHour, int startMinute, int endHour, int endMinute) {
        return new DailyOperatingHours(
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute)
        );
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
