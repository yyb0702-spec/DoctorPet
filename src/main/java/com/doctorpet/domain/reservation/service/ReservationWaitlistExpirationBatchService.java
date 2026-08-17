package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationWaitlistExpirationLock;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;

/** 만료된 OFFERED 제안을 짧은 개별 트랜잭션으로 처리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationWaitlistExpirationBatchService {

    private final ReservationWaitlistRepository reservationWaitlistRepository;
    private final ReservationWaitlistService reservationWaitlistService;
    private final ReservationWaitlistProperties properties;
    private final ReservationWaitlistExpirationLock lock;
    private final Clock clock;

    public int expireOffers() {
        return lock.executeIfAcquired(
                properties.getExpirationLockWaitSeconds(),
                this::expireLockedOffers
        ).orElse(0);
    }

    private int expireLockedOffers() {
        LocalDateTime now = LocalDateTime.now(clock);
        int expired = 0;
        for (Long waitlistId : reservationWaitlistRepository
                .findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
                        ReservationWaitlistStatus.OFFERED,
                        now,
                        PageRequest.of(0, properties.getExpirationBatchSize())
                )
                .stream()
                .map(waitlist -> waitlist.getId())
                .toList()) {
            try {
                if (reservationWaitlistService.expire(waitlistId, now)) {
                    expired++;
                }
            } catch (RuntimeException exception) {
                log.warn("예약 대기열 승급 제안 만료 처리 실패: waitlistId={}", waitlistId, exception);
            }
        }
        return expired;
    }
}
