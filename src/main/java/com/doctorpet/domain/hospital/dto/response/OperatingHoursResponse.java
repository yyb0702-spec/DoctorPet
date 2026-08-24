package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record OperatingHoursResponse(
        Long scheduleId,
        LocalDateTime updatedAt,
        LocalDate effectiveFrom,
        List<DailyOperatingHoursResponse> days
) {

    public static OperatingHoursResponse from(HospitalOperatingSchedule schedule) {
        List<DailyOperatingHoursResponse> days = Arrays.stream(DayOfWeek.values())
                .map(day -> DailyOperatingHoursResponse.from(
                        day,
                        schedule.getOperatingHours().getOrDefault(day, List.of())
                ))
                .toList();

        return new OperatingHoursResponse(
                schedule.getId(),
                schedule.getUpdatedAt(),
                schedule.getEffectiveFrom(),
                days
        );
    }
}
