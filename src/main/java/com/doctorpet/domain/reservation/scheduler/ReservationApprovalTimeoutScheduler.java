package com.doctorpet.domain.reservation.scheduler;

import com.doctorpet.domain.reservation.service.ReservationApprovalTimeoutBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분 주기로 승인 마감 예약의 자동 거절 배치를 실행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationApprovalTimeoutScheduler {

    private final ReservationApprovalTimeoutBatchService batchService;

    @Scheduled(
            fixedDelayString = "#{@reservationApprovalTimeoutProperties.intervalMs}",
            initialDelayString = "#{@reservationApprovalTimeoutProperties.initialDelayMs}"
    )
    public void run() {
        ReservationApprovalTimeoutSummary summary =
                batchService.processBatch();
        if (!summary.locked()) {
            log.debug("예약 승인 타임아웃 배치 스킵(다른 인스턴스 실행 중)");
            return;
        }

        log.info(
                "예약 승인 타임아웃 배치 완료: scanned={}, processed={}, skipped={}, failed={}, maxDelayMs={}",
                summary.scanned(),
                summary.processed(),
                summary.skipped(),
                summary.failed(),
                summary.maxDelayMillis()
        );
    }
}
