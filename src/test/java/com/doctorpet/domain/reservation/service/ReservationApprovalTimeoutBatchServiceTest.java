package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.doctorpet.domain.reservation.config.ReservationApprovalTimeoutProperties;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationApprovalTimeoutLock;
import com.doctorpet.domain.reservation.scheduler.ReservationApprovalTimeoutSummary;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationApprovalTimeoutBatchServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-04T00:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationApprovalTimeoutProcessor processor;

    @Mock
    private ReservationApprovalTimeoutLock lock;

    private ReservationApprovalTimeoutBatchService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReservationApprovalTimeoutProperties properties =
                new ReservationApprovalTimeoutProperties();
        properties.setBatchSize(100);
        properties.setMaxAttempts(2);

        service = new ReservationApprovalTimeoutBatchService(
                reservationRepository,
                processor,
                lock,
                properties,
                FIXED_CLOCK,
                new SimpleMeterRegistry()
        );

        when(lock.executeIfAcquired(anyInt(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(1);
                    return Optional.of(action.get());
                });
    }

    @Test
    @DisplayName("한 예약이 최종 실패해도 다음 예약을 처리하고 다음 배치에서 다시 시도한다")
    void partialFailure_doesNotStopBatchAndRetriesNextRun() {
        Reservation failedTarget = target(1L);
        Reservation normalTarget = target(2L);
        given(reservationRepository.findApprovalTimeoutTargets(
                eq(ReservationStatus.REQUESTED),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).willReturn(
                List.of(failedTarget, normalTarget),
                List.of(failedTarget)
        );
        given(processor.process(eq(1L), any(LocalDateTime.class)))
                .willThrow(new IllegalStateException("일시 실패"))
                .willThrow(new IllegalStateException("재시도 실패"))
                .willReturn(ReservationApprovalTimeoutProcessor.Result.PROCESSED);
        given(processor.process(eq(2L), any(LocalDateTime.class)))
                .willReturn(ReservationApprovalTimeoutProcessor.Result.PROCESSED);

        ReservationApprovalTimeoutSummary first = service.processBatch();
        ReservationApprovalTimeoutSummary second = service.processBatch();

        assertThat(first.processed()).isEqualTo(1);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(second.processed()).isEqualTo(1);
        assertThat(second.failed()).isZero();
        verify(processor, org.mockito.Mockito.times(3))
                .process(eq(1L), any(LocalDateTime.class));
        verify(processor).process(eq(2L), any(LocalDateTime.class));
    }

    private Reservation target(Long id) {
        LocalDateTime requestedAt = LocalDateTime.now(FIXED_CLOCK)
                .minusHours(2);
        Reservation reservation = Reservation.request(
                id + 10,
                1L,
                id + 20,
                id + 30,
                1L,
                "초코",
                "DOG",
                requestedAt
        );
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }
}
