package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.scheduler.HospitalSlotGenerationLock;
import com.doctorpet.domain.hospital.scheduler.HospitalSlotGenerationSummary;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalSlotGenerationBatchService {

    private final HospitalOperatingScheduleRepository scheduleRepository;
    private final HospitalOperatingHoursApplicationService operatingHoursService;
    private final HospitalSlotGenerationLock lock;

    public HospitalSlotGenerationSummary generateRange(
            LocalDate fromDate,
            LocalDate toDate
    ) {
        int targetDates = Math.toIntExact(
                ChronoUnit.DAYS.between(fromDate, toDate) + 1
        );
        Optional<HospitalSlotGenerationSummary> result = lock.executeIfAcquired(
                () -> generateLocked(fromDate, toDate, targetDates)
        );
        return result.orElseGet(
                () -> HospitalSlotGenerationSummary.lockSkipped(targetDates)
        );
    }

    private HospitalSlotGenerationSummary generateLocked(
            LocalDate fromDate,
            LocalDate toDate,
            int targetDates
    ) {
        int targetTasks = 0;
        int succeededTasks = 0;
        int failedTasks = 0;
        int createdSlots = 0;

        for (LocalDate businessDate = fromDate;
             !businessDate.isAfter(toDate);
             businessDate = businessDate.plusDays(1)) {
            List<Long> hospitalIds = scheduleRepository
                    .findHospitalIdsWithEffectiveSchedule(businessDate);
            for (Long hospitalId : hospitalIds) {
                targetTasks++;
                try {
                    createdSlots += operatingHoursService.createSlots(
                            hospitalId,
                            businessDate
                    );
                    succeededTasks++;
                } catch (RuntimeException exception) {
                    failedTasks++;
                    log.error(
                            "병원 예약 슬롯 생성 실패: hospitalId={}, businessDate={}",
                            hospitalId,
                            businessDate,
                            exception
                    );
                }
            }
        }

        return new HospitalSlotGenerationSummary(
                true,
                targetDates,
                targetTasks,
                succeededTasks,
                failedTasks,
                createdSlots
        );
    }
}
