package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalTemporaryClosureRepository;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ai.openai.api-key=test-key",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class HospitalSlotGenerationBatchIntegrationTest {

    @Autowired
    private HospitalSlotGenerationBatchService batchService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Autowired
    private HospitalTemporaryClosureRepository closureRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    private final List<Long> hospitalIds = new ArrayList<>();
    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> closureIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long hospitalId : hospitalIds) {
            List<ReservationSlot> slots = slotRepository.findSlotsInRange(
                    hospitalId,
                    LocalDate.of(2026, 8, 1).atStartOfDay(),
                    LocalDate.of(2026, 9, 1).atStartOfDay()
            );
            slotRepository.deleteAll(slots);
        }
        slotRepository.flush();
        closureRepository.deleteAllById(closureIds);
        closureRepository.flush();
        scheduleRepository.deleteAllById(scheduleIds);
        scheduleRepository.flush();
        hospitalRepository.deleteAllById(hospitalIds);
        hospitalRepository.flush();
        hospitalIds.clear();
        scheduleIds.clear();
        closureIds.clear();
    }

    @Test
    void generateUsesFutureScheduleSkipsClosureAndIsIdempotent() {
        LocalDate businessDate = LocalDate.of(2026, 8, 24);
        Hospital operatingHospital = saveHospital("SLOT-BATCH-OPERATING");
        Hospital closedHospital = saveHospital("SLOT-BATCH-CLOSED");
        Hospital nonPartnerHospital = saveHospital("SLOT-BATCH-NON-PARTNER", false);

        saveSchedule(schedule(
                operatingHospital,
                businessDate.minusDays(10),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(10, 0))
        ));
        saveSchedule(schedule(
                operatingHospital,
                businessDate,
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(11, 0), LocalTime.of(12, 0))
        ));
        saveSchedule(schedule(
                closedHospital,
                businessDate.minusDays(1),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(13, 0), LocalTime.of(14, 0))
        ));
        saveSchedule(schedule(
                nonPartnerHospital,
                businessDate.minusDays(1),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(15, 0), LocalTime.of(16, 0))
        ));
        HospitalTemporaryClosure closure = closureRepository.saveAndFlush(
                HospitalTemporaryClosure.create(closedHospital, businessDate)
        );
        closureIds.add(closure.getId());

        var first = batchService.generate(businessDate);
        var second = batchService.generate(businessDate);

        assertThat(first.targetHospitals()).isEqualTo(2);
        assertThat(first.succeededHospitals()).isEqualTo(2);
        assertThat(first.failedHospitals()).isZero();
        assertThat(first.createdSlots()).isEqualTo(2);
        assertThat(second.createdSlots()).isZero();
        assertThat(slotRepository.findBusinessDateSlots(
                operatingHospital.getId(),
                businessDate
        )).extracting(ReservationSlot::getStartAt)
                .containsExactly(
                        businessDate.atTime(11, 0),
                        businessDate.atTime(11, 30)
                );
        assertThat(slotRepository.findBusinessDateSlots(
                closedHospital.getId(),
                businessDate
        )).isEmpty();
        assertThat(slotRepository.findBusinessDateSlots(
                nonPartnerHospital.getId(),
                businessDate
        )).isEmpty();
    }

    private Hospital saveHospital(String managementNumber) {
        return saveHospital(managementNumber, true);
    }

    private Hospital saveHospital(String managementNumber, boolean partner) {
        Hospital hospital = Hospital.createFromPublicData(
                managementNumber + System.nanoTime(),
                "SLOT-BATCH-LOCAL-GOV",
                "슬롯 배치 테스트 병원",
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
        if (partner) {
            hospital.markAsPartner();
        }
        Hospital saved = hospitalRepository.saveAndFlush(hospital);
        hospitalIds.add(saved.getId());
        return saved;
    }

    private HospitalOperatingSchedule schedule(
            Hospital hospital,
            LocalDate effectiveFrom,
            DayOfWeek dayOfWeek,
            DailyOperatingHours operatingHours
    ) {
        return HospitalOperatingSchedule.create(
                hospital,
                effectiveFrom,
                Map.of(dayOfWeek, List.of(operatingHours))
        );
    }

    private void saveSchedule(HospitalOperatingSchedule schedule) {
        scheduleIds.add(scheduleRepository.saveAndFlush(schedule).getId());
    }
}
