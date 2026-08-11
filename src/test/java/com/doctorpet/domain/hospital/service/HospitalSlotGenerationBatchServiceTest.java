package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.scheduler.HospitalSlotGenerationLock;
import com.doctorpet.domain.hospital.scheduler.HospitalSlotGenerationSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalSlotGenerationBatchServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 24);

    @Mock
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Mock
    private HospitalOperatingHoursApplicationService operatingHoursService;

    @Mock
    private HospitalSlotGenerationLock lock;

    @InjectMocks
    private HospitalSlotGenerationBatchService batchService;

    @Test
    void generateContinuesWhenOneHospitalFails() {
        LocalDate nextDate = BUSINESS_DATE.plusDays(1);
        given(scheduleRepository.findHospitalIdsWithEffectiveSchedule(BUSINESS_DATE))
                .willReturn(List.of(1L, 2L));
        given(scheduleRepository.findHospitalIdsWithEffectiveSchedule(nextDate))
                .willReturn(List.of(2L, 3L));
        given(operatingHoursService.createSlots(1L, BUSINESS_DATE)).willReturn(2);
        given(operatingHoursService.createSlots(2L, BUSINESS_DATE))
                .willThrow(new IllegalStateException("test failure"));
        given(operatingHoursService.createSlots(2L, nextDate)).willReturn(1);
        given(operatingHoursService.createSlots(3L, nextDate)).willReturn(4);
        given(lock.executeIfAcquired(any())).willAnswer(invocation -> {
            Supplier<HospitalSlotGenerationSummary> action = invocation.getArgument(0);
            return Optional.of(action.get());
        });

        var summary = batchService.generateRange(BUSINESS_DATE, nextDate);

        assertThat(summary.locked()).isTrue();
        assertThat(summary.targetDates()).isEqualTo(2);
        assertThat(summary.targetTasks()).isEqualTo(4);
        assertThat(summary.succeededTasks()).isEqualTo(3);
        assertThat(summary.failedTasks()).isEqualTo(1);
        assertThat(summary.createdSlots()).isEqualTo(7);
        verify(operatingHoursService).createSlots(3L, nextDate);
    }

    @Test
    void generateReturnsSkippedSummaryWhenAnotherInstanceOwnsLock() {
        given(lock.executeIfAcquired(any())).willReturn(Optional.empty());

        var summary = batchService.generateRange(BUSINESS_DATE, BUSINESS_DATE.plusDays(13));

        assertThat(summary.locked()).isFalse();
        assertThat(summary.targetDates()).isEqualTo(14);
        assertThat(summary.targetTasks()).isZero();
    }
}
