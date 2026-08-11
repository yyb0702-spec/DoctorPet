package com.doctorpet.domain.hospital.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.service.HospitalSlotGenerationBatchService;
import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalSlotGenerationSchedulerTest {

    @Mock
    private HospitalSlotGenerationBatchService batchService;

    @InjectMocks
    private HospitalSlotGenerationScheduler scheduler;

    @Test
    void generatePublishedRangeLastDayCreatesTodayPlusThirteen() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-10T15:00:00Z"),
                TimePolicy.SEOUL_ZONE_ID
        );
        scheduler = new HospitalSlotGenerationScheduler(batchService, clock);
        LocalDate businessDate = LocalDate.of(2026, 8, 24);
        given(batchService.generate(businessDate))
                .willReturn(new HospitalSlotGenerationSummary(1, 1, 0, 2));

        scheduler.generatePublishedRangeLastDay();

        verify(batchService).generate(businessDate);
    }
}
