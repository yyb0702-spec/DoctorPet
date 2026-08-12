package com.doctorpet.domain.hospital.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record OperatingPeriodRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
