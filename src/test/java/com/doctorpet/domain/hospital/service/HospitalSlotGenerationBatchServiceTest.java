package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import java.time.LocalDate;
import java.util.List;
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

    @InjectMocks
    private HospitalSlotGenerationBatchService batchService;

    @Test
    void generateContinuesWhenOneHospitalFails() {
        given(scheduleRepository.findHospitalIdsWithEffectiveSchedule(BUSINESS_DATE))
                .willReturn(List.of(1L, 2L, 3L));
        given(operatingHoursService.createSlots(1L, BUSINESS_DATE)).willReturn(2);
        given(operatingHoursService.createSlots(2L, BUSINESS_DATE))
                .willThrow(new IllegalStateException("test failure"));
        given(operatingHoursService.createSlots(3L, BUSINESS_DATE)).willReturn(4);

        var summary = batchService.generate(BUSINESS_DATE);

        assertThat(summary.targetHospitals()).isEqualTo(3);
        assertThat(summary.succeededHospitals()).isEqualTo(2);
        assertThat(summary.failedHospitals()).isEqualTo(1);
        assertThat(summary.createdSlots()).isEqualTo(6);
        verify(operatingHoursService).createSlots(3L, BUSINESS_DATE);
    }
}
