package com.doctorpet.domain.reservation.scheduler;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.service.ReservationWaitlistExpirationBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분 주기로 만료된 승급 제안을 다음 FIFO 대기자에게 넘긴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationWaitlistExpirationScheduler {

    private final ReservationWaitlistExpirationBatchService batchService;
    private final ReservationWaitlistProperties properties;

    @Scheduled(
            fixedDelayString = "#{@reservationWaitlistProperties.expirationIntervalMs}",
            initialDelayString = "#{@reservationWaitlistProperties.expirationInitialDelayMs}"
    )
    public void run() {
        int expired = batchService.expireOffers();
        if (expired > 0) {
            log.info("예약 대기열 승급 제안 만료 처리 완료: expired={}", expired);
        }
    }
}
