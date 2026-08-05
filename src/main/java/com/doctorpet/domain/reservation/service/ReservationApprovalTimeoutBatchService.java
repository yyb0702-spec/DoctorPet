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
        BatchAccumulator accumulator = new BatchAccumulator();
        int totalLimit = properties.getMaxScannedPerRun();
        int retryQuota = totalLimit > 1 ? Math.max(1, totalLimit / 5) : 0;
        int normalQuota = totalLimit - retryQuota;
        GroupState normal = processGroup(now, normalQuota, false, new GroupState(), accumulator);
        GroupState retry = processGroup(now, retryQuota, true, new GroupState(), accumulator);
        if (normal.scanned < normalQuota) {
            processGroup(now, normalQuota - normal.scanned, true, retry, accumulator);
        }
        if (retry.scanned < retryQuota) {
            processGroup(now, retryQuota - retry.scanned, false, normal, accumulator);
        }

        return new ReservationApprovalTimeoutSummary(
                true,
                accumulator.scanned,
                accumulator.processed,
                accumulator.skipped,
                accumulator.failed,
                accumulator.maxDelayMillis
        );
    }

    private GroupState processGroup(
            LocalDateTime now,
            int limit,
            boolean retryGroup,
            GroupState state,
            BatchAccumulator accumulator
    ) {
        int targetCount = state.scanned + limit;
        while (state.scanned < targetCount) {
            int pageSize = Math.min(properties.getBatchSize(), targetCount - state.scanned);
            List<Reservation> targets = retryGroup
                    ? findRetryTargets(now, state, pageSize)
                    : findNormalTargets(now, state, pageSize);
            if (targets.isEmpty()) break;
            for (Reservation target : targets) {
                state.scanned++;
                accumulator.scanned++;
                long delayMillis = Math.max(0L, Duration.between(
                        target.getApprovalDeadlineAt(), now).toMillis());
                delaySummary.record(delayMillis);
                accumulator.maxDelayMillis = Math.max(accumulator.maxDelayMillis, delayMillis);
                ReservationApprovalTimeoutProcessor.Result result = processWithRetry(target.getId(), now);
                switch (result) {
                    case PROCESSED -> { accumulator.processed++; processedCounter.increment(); }
                    case SKIPPED -> { accumulator.skipped++; skippedCounter.increment(); }
                    case FAILED -> {
                        try {
                            processor.deferRetry(target.getId(), now.plusNanos(
                                    properties.getFailureRetryDelayMs() * 1_000_000L));
                        } catch (RuntimeException exception) {
                            log.error("예약 승인 타임아웃 재시도 시각 저장 실패: reservationId={}", target.getId(), exception);
                        }
                        accumulator.failed++;
                        failedCounter.increment();
                    }
                }
                state.cursorDeadline = target.getApprovalDeadlineAt();
                state.cursorId = target.getId();
            }
        }
        return state;
    }

    private List<Reservation> findNormalTargets(LocalDateTime now, GroupState state, int size) {
        PageRequest page = PageRequest.of(0, size);
        return state.cursorDeadline == null
                ? reservationRepository.findApprovalTimeoutTargets(ReservationStatus.REQUESTED, now, page)
                : reservationRepository.findApprovalTimeoutTargetsAfter(ReservationStatus.REQUESTED, now, state.cursorDeadline, state.cursorId, page);
    }

    private List<Reservation> findRetryTargets(LocalDateTime now, GroupState state, int size) {
        PageRequest page = PageRequest.of(0, size);
        return state.cursorDeadline == null
                ? reservationRepository.findApprovalTimeoutRetryTargets(ReservationStatus.REQUESTED, now, page)
                : reservationRepository.findApprovalTimeoutRetryTargetsAfter(ReservationStatus.REQUESTED, now, state.cursorDeadline, state.cursorId, page);
    }

    private static final class GroupState {
        private int scanned;
        private LocalDateTime cursorDeadline;
        private Long cursorId;
    }

    private static final class BatchAccumulator {
        private int scanned;
        private int processed;
        private int skipped;
        private int failed;
        private long maxDelayMillis;
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
