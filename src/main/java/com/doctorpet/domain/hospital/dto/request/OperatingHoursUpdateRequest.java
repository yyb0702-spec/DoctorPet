package com.doctorpet.domain.hospital.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OperatingHoursUpdateRequest(
        @NotNull LocalDate desiredEffectiveFrom,
        @NotNull OperatingHoursSaveMode saveMode,
        @Positive Long targetScheduleId,
        LocalDateTime expectedUpdatedAt,
        @NotNull @Size(min = 7, max = 7)
        List<@NotNull @Valid DailyOperatingHoursRequest> days
) {
}
