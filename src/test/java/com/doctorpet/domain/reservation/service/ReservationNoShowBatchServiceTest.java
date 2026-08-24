package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.doctorpet.domain.reservation.config.ReservationNoShowProperties;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationNoShowTarget;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationNoShowLock;
import com.doctorpet.domain.reservation.scheduler.ReservationNoShowSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReservationNoShowBatchServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-05T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    @Mock ReservationRepository reservationRepository;
    @Mock ReservationNoShowProcessor processor;
    @Mock ReservationNoShowLock lock;
    private ReservationNoShowBatchService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReservationNoShowProperties properties = new ReservationNoShowProperties();
        properties.setBatchSize(1);
        properties.setMaxScannedPerRun(2);
        properties.setMaxAttempts(2);
        service = new ReservationNoShowBatchService(
                reservationRepository, processor, lock,
                properties, CLOCK, new SimpleMeterRegistry());
        when(lock.executeIfAcquired(anyInt(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return Optional.of(action.get());
        });
    }

    @Test
    @DisplayName("한 예약이 재시도 후 실패해도 다음 예약은 계속 처리한다")
    void partialFailure_doesNotStopLaterTarget() {
        ReservationNoShowTarget failed = target(1L, 20);
        ReservationNoShowTarget normal = target(2L, 19);
        given(reservationRepository.findAutoNoShowTargets(
                eq(ReservationStatus.CONFIRMED),
                eq(ReservationStatus.NO_SHOW_PENDING),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(List.of(failed));
        given(reservationRepository.findAutoNoShowTargetsAfter(
                eq(ReservationStatus.CONFIRMED),
                eq(ReservationStatus.NO_SHOW_PENDING),
                any(LocalDateTime.class), any(LocalDateTime.class),
                any(LocalDateTime.class), any(), any(Pageable.class)))
                .willReturn(List.of(normal));
        given(processor.process(eq(1L), any(LocalDateTime.class)))
                .willThrow(new IllegalStateException("일시 실패"));
        given(processor.process(eq(2L), any(LocalDateTime.class)))
                .willReturn(ReservationNoShowProcessor.Result.PROCESSED);

        ReservationNoShowSummary result = service.processBatch();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isEqualTo(1);
    }

    private ReservationNoShowTarget target(Long id, long minutesAgo) {
        return new TestNoShowTarget(
                id,
                ReservationStatus.CONFIRMED,
                LocalDateTime.now(CLOCK).minusMinutes(minutesAgo)
        );
    }

    private record TestNoShowTarget(
            Long reservationId,
            ReservationStatus status,
            LocalDateTime slotStartAt
    ) implements ReservationNoShowTarget {

        @Override
        public Long getReservationId() {
            return reservationId;
        }

        @Override
        public ReservationStatus getStatus() {
            return status;
        }

        @Override
        public LocalDateTime getSlotStartAt() {
            return slotStartAt;
        }
    }
}
