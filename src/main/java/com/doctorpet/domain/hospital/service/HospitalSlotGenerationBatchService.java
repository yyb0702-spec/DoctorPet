package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.scheduler.HospitalSlotGenerationSummary;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalSlotGenerationBatchService {

    private final HospitalOperatingScheduleRepository scheduleRepository;
    private final HospitalOperatingHoursApplicationService operatingHoursService;

    public HospitalSlotGenerationSummary generate(LocalDate businessDate) {
        List<Long> hospitalIds = scheduleRepository
                .findHospitalIdsWithEffectiveSchedule(businessDate);
        int succeededHospitals = 0;
        int failedHospitals = 0;
        int createdSlots = 0;

        for (Long hospitalId : hospitalIds) {
            try {
                createdSlots += operatingHoursService.createSlots(hospitalId, businessDate);
                succeededHospitals++;
            } catch (RuntimeException exception) {
                failedHospitals++;
                log.error(
                        "병원 예약 슬롯 생성 실패: hospitalId={}, businessDate={}",
                        hospitalId,
                        businessDate,
                        exception
                );
            }
        }

        return new HospitalSlotGenerationSummary(
                hospitalIds.size(),
                succeededHospitals,
                failedHospitals,
                createdSlots
        );
    }
}
