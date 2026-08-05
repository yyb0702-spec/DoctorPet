package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.config.ReservationApprovalTimeoutProperties;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationApprovalTimeoutLock;
import com.doctorpet.domain.reservation.scheduler.ReservationApprovalTimeoutSummary;
import com.doctorpet.global.exception.ServiceException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** 마감 대상을 조회하고 각 예약을 짧은 개별 트랜잭션으로 처리한다. */
@Slf4j
@Service
public class ReservationApprovalTimeoutBatchService {

    private final ReservationRepository reservationRepository;
    private final ReservationApprovalTimeoutProcessor processor;
    private final ReservationApprovalTimeoutLock lock;
    private final ReservationApprovalTimeoutProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Counter processedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;
    private final Counter lockSkippedCounter;
    private final DistributionSummary delaySummary;
    private final Timer batchTimer;

    public ReservationApprovalTimeoutBatchService(
            ReservationRepository reservationRepository,
            ReservationApprovalTimeoutProcessor processor,
            ReservationApprovalTimeoutLock lock,
            ReservationApprovalTimeoutProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.reservationRepository = reservationRepository;
        this.processor = processor;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.processedCounter = meterRegistry.counter(
                "reservation.approval.timeout.processed"
        );
        this.skippedCounter = meterRegistry.counter(
                "reservation.approval.timeout.skipped"
        );
        this.failedCounter = meterRegistry.counter(
                "reservation.approval.timeout.failed"
        );
        this.lockSkippedCounter = meterRegistry.counter(
                "reservation.approval.timeout.lock.skipped"
        );
        this.delaySummary = DistributionSummary.builder(
                        "reservation.approval.timeout.delay"
                )
                .baseUnit("milliseconds")
                .register(meterRegistry);
        this.batchTimer = meterRegistry.timer(
                "reservation.approval.timeout.batch.duration"
        );
    }

    public ReservationApprovalTimeoutSummary processBatch() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<ReservationApprovalTimeoutSummary> result =
                    lock.executeIfAcquired(
                            properties.getLockWaitSeconds(),
                            this::processLocked
                    );
            if (result.isEmpty()) {
                lockSkippedCounter.increment();
                return ReservationApprovalTimeoutSummary.lockSkipped();
            }
            return result.get();
        } finally {
            sample.stop(batchTimer);
        }
    }

    private ReservationApprovalTimeoutSummary processLocked() {
        LocalDateTime now = LocalDateTime.now(clock);
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        int scanned = 0;
        long maxDelayMillis = 0L;
        LocalDateTime cursorDeadline = null;
        Long cursorId = null;

        while (scanned < properties.getMaxScannedPerRun()) {
            int pageSize = Math.min(
                    properties.getBatchSize(),
                    properties.getMaxScannedPerRun() - scanned
            );
            List<Reservation> targets = findNextTargets(
                    now,
                    cursorDeadline,
                    cursorId,
                    pageSize
            );
            if (targets.isEmpty()) {
                break;
            }

            for (Reservation target : targets) {
                scanned++;
                long delayMillis = Math.max(
                        0L,
                        Duration.between(
                                target.getApprovalDeadlineAt(),
                                now
                        ).toMillis()
                );
                delaySummary.record(delayMillis);
                maxDelayMillis = Math.max(maxDelayMillis, delayMillis);

                ReservationApprovalTimeoutProcessor.Result result =
                        processWithRetry(target.getId(), now);
                switch (result) {
                    case PROCESSED -> {
                        processed++;
                        processedCounter.increment();
                    }
                    case SKIPPED -> {
                        skipped++;
                        skippedCounter.increment();
                    }
                    case FAILED -> {
                        failed++;
                        failedCounter.increment();
                    }
                }
            }

            Reservation lastTarget = targets.get(targets.size() - 1);
            cursorDeadline = lastTarget.getApprovalDeadlineAt();
            cursorId = lastTarget.getId();
        }

        return new ReservationApprovalTimeoutSummary(
                true,
                scanned,
                processed,
                skipped,
                failed,
                maxDelayMillis
        );
    }

    private List<Reservation> findNextTargets(
            LocalDateTime now,
            LocalDateTime cursorDeadline,
            Long cursorId,
            int pageSize
    ) {
        PageRequest page = PageRequest.of(0, pageSize);
        if (cursorDeadline == null) {
            return reservationRepository.findApprovalTimeoutTargets(
                    ReservationStatus.REQUESTED,
                    now,
                    page
            );
        }
        return reservationRepository.findApprovalTimeoutTargetsAfter(
                ReservationStatus.REQUESTED,
                now,
                cursorDeadline,
                cursorId,
                page
        );
    }

    private ReservationApprovalTimeoutProcessor.Result processWithRetry(
            Long reservationId,
            LocalDateTime now
    ) {
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                return processor.process(reservationId, now);
            } catch (RuntimeException exception) {
                if (isNonRetryable(exception)) {
                    log.warn(
                            "예약 승인 타임아웃 처리 불가: reservationId={}, reason={}",
                            reservationId,
                            exception.getMessage()
                    );
                    return ReservationApprovalTimeoutProcessor.Result.FAILED;
                }
                if (attempt == properties.getMaxAttempts()) {
                    log.warn(
                            "예약 승인 타임아웃 처리 실패: reservationId={}, attempts={}",
                            reservationId,
                            attempt,
                            exception
                    );
                    return ReservationApprovalTimeoutProcessor.Result.FAILED;
                }
                log.debug(
                        "예약 승인 타임아웃 처리 재시도: reservationId={}, attempt={}",
                        reservationId,
                        attempt,
                        exception
                );
            }
        }
        return ReservationApprovalTimeoutProcessor.Result.FAILED;
    }

    private boolean isNonRetryable(RuntimeException exception) {
        if (!(exception instanceof ServiceException serviceException)) {
            return false;
        }
        return serviceException.getErrorCode() == ReservationErrorCode.RESERVATION_NOT_FOUND
                || serviceException.getErrorCode() == SlotErrorCode.SLOT_NOT_FOUND;
    }
}
