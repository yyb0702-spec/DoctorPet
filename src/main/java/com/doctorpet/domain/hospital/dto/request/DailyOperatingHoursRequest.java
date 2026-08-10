package com.doctorpet.domain.hospital.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.util.List;

public record DailyOperatingHoursRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull List<@Valid OperatingPeriodRequest> periods
) {
}
