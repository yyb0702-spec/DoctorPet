package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import java.time.DayOfWeek;
import java.util.List;

public record DailyOperatingHoursResponse(
        DayOfWeek dayOfWeek,
        List<OperatingPeriodResponse> periods
) {

    public static DailyOperatingHoursResponse from(
            DayOfWeek dayOfWeek,
            List<DailyOperatingHours> operatingHours
    ) {
        return new DailyOperatingHoursResponse(
                dayOfWeek,
                operatingHours.stream()
                        .map(OperatingPeriodResponse::from)
                        .toList()
        );
    }
}
