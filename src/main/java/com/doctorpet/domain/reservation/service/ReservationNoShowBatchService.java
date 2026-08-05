package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.config.ReservationNoShowProperties;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationNoShowTarget;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationNoShowLock;
import com.doctorpet.domain.reservation.scheduler.ReservationNoShowSummary;
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

@Slf4j
@Service
public class ReservationNoShowBatchService {

    private final ReservationRepository reservationRepository;
    private final ReservationNoShowProcessor processor;
    private final ReservationNoShowLock lock;
    private final ReservationNoShowProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Counter processedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;
    private final Counter lockSkippedCounter;
    private final DistributionSummary delaySummary;
    private final Timer batchTimer;

    public ReservationNoShowBatchService(
            ReservationRepository reservationRepository,
            ReservationNoShowProcessor processor,
            ReservationNoShowLock lock,
            ReservationNoShowProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.reservationRepository = reservationRepository;
        this.processor = processor;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.processedCounter = meterRegistry.counter("reservation.no.show.processed");
        this.skippedCounter = meterRegistry.counter("reservation.no.show.skipped");
        this.failedCounter = meterRegistry.counter("reservation.no.show.failed");
        this.lockSkippedCounter = meterRegistry.counter("reservation.no.show.lock.skipped");
        this.delaySummary = DistributionSummary.builder("reservation.no.show.delay")
                .baseUnit("milliseconds").register(meterRegistry);
        this.batchTimer = meterRegistry.timer("reservation.no.show.batch.duration");
    }

    public ReservationNoShowSummary processBatch() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<ReservationNoShowSummary> result = lock.executeIfAcquired(
                    properties.getLockWaitSeconds(), this::processLocked);
            if (result.isEmpty()) {
                lockSkippedCounter.increment();
                return ReservationNoShowSummary.lockSkipped();
            }
            return result.get();
        } finally {
            sample.stop(batchTimer);
        }
    }

    private ReservationNoShowSummary processLocked() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusMinutes(properties.getGraceMinutes());
        int scanned = 0;
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        long maxDelay = 0L;
        LocalDateTime cursorStartAt = null;
        Long cursorId = null;

        while (scanned < properties.getMaxScannedPerRun()) {
            int pageSize = Math.min(properties.getBatchSize(),
                    properties.getMaxScannedPerRun() - scanned);
            List<ReservationNoShowTarget> targets = cursorStartAt == null
                    ? reservationRepository.findAutoNoShowTargets(
                    ReservationStatus.CONFIRMED, cutoff, PageRequest.of(0, pageSize))
                    : reservationRepository.findAutoNoShowTargetsAfter(
                    ReservationStatus.CONFIRMED, cutoff, cursorStartAt,
                    cursorId, PageRequest.of(0, pageSize));
            if (targets.isEmpty()) break;

            for (ReservationNoShowTarget target : targets) {
                scanned++;
                cursorStartAt = target.getSlotStartAt();
                cursorId = target.getReservationId();
                try {
                    long delay = Math.max(0L, Duration.between(
                            target.getSlotStartAt().plusMinutes(properties.getGraceMinutes()),
                            now
                    ).toMillis());
                    delaySummary.record(delay);
                    maxDelay = Math.max(maxDelay, delay);
                    ReservationNoShowProcessor.Result result = processWithRetry(
                            target.getReservationId(),
                            now
                    );
                    if (result == ReservationNoShowProcessor.Result.PROCESSED) {
                        processed++;
                        processedCounter.increment();
                    } else {
                        skipped++;
                        skippedCounter.increment();
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    failedCounter.increment();
                    log.warn("예약 자동 노쇼 단건 처리 실패: reservationId={}",
                            target.getReservationId(), exception);
                }
            }
        }
        return new ReservationNoShowSummary(true, scanned, processed, skipped, failed, maxDelay);
    }

    private ReservationNoShowProcessor.Result processWithRetry(Long reservationId, LocalDateTime now) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                return processor.process(reservationId, now);
            } catch (RuntimeException exception) {
                last = exception;
                log.debug("예약 자동 노쇼 처리 재시도: reservationId={}, attempt={}",
                        reservationId, attempt, exception);
            }
        }
        throw last == null ? new IllegalStateException("자동 노쇼 처리 실패") : last;
    }
}
