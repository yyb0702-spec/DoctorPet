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
    public void maintainPublishedRange() {
        LocalDate fromDate = LocalDate.now(applicationClock);
        LocalDate toDate = fromDate.plusDays(PUBLISHED_RANGE_LAST_DAY_OFFSET);
        HospitalSlotGenerationSummary summary = batchService.generateRange(fromDate, toDate);

        if (!summary.locked()) {
            log.info("병원 예약 슬롯 일일 생성 건너뜀: 다른 인스턴스 실행 중");
            return;
        }

        log.info(
                "병원 예약 슬롯 공개 범위 보정 완료: fromDate={}, toDate={}, targetDates={}, tasks={}, succeeded={}, failed={}, createdSlots={}",
                fromDate,
                toDate,
                summary.targetDates(),
                summary.targetTasks(),
                summary.succeededTasks(),
                summary.failedTasks(),
                summary.createdSlots()
        );
    }
}
