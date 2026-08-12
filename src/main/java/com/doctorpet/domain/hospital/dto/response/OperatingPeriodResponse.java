package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import java.time.LocalTime;

public record OperatingPeriodResponse(
        LocalTime startTime,
        LocalTime endTime
) {

    public static OperatingPeriodResponse from(DailyOperatingHours operatingHours) {
        return new OperatingPeriodResponse(
                operatingHours.openTime(),
                operatingHours.closeTime()
        );
    }
}
