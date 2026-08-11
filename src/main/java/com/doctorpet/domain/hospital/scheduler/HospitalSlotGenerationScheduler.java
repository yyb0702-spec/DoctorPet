package com.doctorpet.domain.hospital.scheduler;

import com.doctorpet.domain.hospital.service.HospitalSlotGenerationBatchService;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalSlotGenerationScheduler {

    private static final int PUBLISHED_RANGE_LAST_DAY_OFFSET = 13;

    private final HospitalSlotGenerationBatchService batchService;
    private final Clock applicationClock;

    @Scheduled(
            cron = "${hospital.slot-generation.cron:0 5 0 * * *}",
            zone = "Asia/Seoul"
    )
    public void generatePublishedRangeLastDay() {
        LocalDate businessDate = LocalDate.now(applicationClock)
                .plusDays(PUBLISHED_RANGE_LAST_DAY_OFFSET);
        HospitalSlotGenerationSummary summary = batchService.generate(businessDate);

        log.info(
                "병원 예약 슬롯 일일 생성 완료: businessDate={}, targets={}, succeeded={}, failed={}, createdSlots={}",
                businessDate,
                summary.targetHospitals(),
                summary.succeededHospitals(),
                summary.failedHospitals(),
                summary.createdSlots()
        );
    }
}
