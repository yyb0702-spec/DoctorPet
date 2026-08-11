package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
@Transactional
class ReservationSlotBusinessDateMigrationIntegrationTest {

    @Autowired
    private ReservationSlotBusinessDateMigrationRunner migrationRunner;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalDetailRepository hospitalDetailRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("기존 야간 예약 슬롯은 실제 운영이 시작된 전날을 영업 기준일로 백필한다")
    void overnightSlotUsesPreviousBusinessDate() throws Exception {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital());
        hospitalDetailRepository.saveAndFlush(HospitalDetail.create(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(20, 0),
                                LocalTime.of(2, 0)
                        ),
                        DayOfWeek.TUESDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(18, 0)
                        )
                ),
                false,
                false,
                true,
                false
        ));

        ReservationSlot overnight = ReservationSlot.create(
                hospital.getId(),
                LocalDateTime.of(2026, 8, 11, 1, 0),
                LocalDateTime.of(2026, 8, 11, 1, 30)
        );
        overnight.reserve();
        ReservationSlot daytime = ReservationSlot.create(
                hospital.getId(),
                LocalDateTime.of(2026, 8, 11, 10, 0),
                LocalDateTime.of(2026, 8, 11, 10, 30)
        );
        reservationSlotRepository.saveAllAndFlush(List.of(
                overnight,
                daytime
        ));

        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                ReservationSlotBusinessDateMigrationRunner
                        .OVERNIGHT_BACKFILL_MIGRATION_KEY
        );

        migrationRunner.run(null);
        entityManager.clear();

        ReservationSlot corrected = reservationSlotRepository
                .findById(overnight.getId())
                .orElseThrow();
        ReservationSlot unchanged = reservationSlotRepository
                .findById(daytime.getId())
                .orElseThrow();
        assertThat(corrected.getBusinessDate())
                .isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(unchanged.getBusinessDate())
                .isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                ReservationSlotBusinessDateMigrationRunner
                        .OVERNIGHT_BACKFILL_MIGRATION_KEY
        )).isEqualTo(1);
    }

    private Hospital createHospital() {
        return Hospital.createFromPublicData(
                "OVERNIGHT-BUSINESS-DATE-" + System.nanoTime(),
                "TEST-LOCAL-GOV",
                "야간 슬롯 백필 테스트 동물병원",
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
