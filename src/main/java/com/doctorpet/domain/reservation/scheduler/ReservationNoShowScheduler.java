package com.doctorpet.domain.reservation.scheduler;

import com.doctorpet.domain.reservation.service.ReservationNoShowBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationNoShowScheduler {

    private final ReservationNoShowBatchService batchService;

    @Scheduled(
            fixedDelayString = "#{@reservationNoShowProperties.intervalMs}",
            initialDelayString = "#{@reservationNoShowProperties.initialDelayMs}"
    )
    public void run() {
        ReservationNoShowSummary summary = batchService.processBatch();
        if (!summary.locked()) {
            log.debug("예약 자동 노쇼 배치 스킵(다른 인스턴스 실행 중)");
            return;
        }
        log.info("예약 자동 노쇼 배치 완료: scanned={}, processed={}, skipped={}, failed={}, maxDelayMs={}",
                summary.scanned(), summary.processed(), summary.skipped(),
                summary.failed(), summary.maxDelayMillis());
    }
}
