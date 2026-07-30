package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record HospitalBusinessHourResponse(
        DayOfWeek dayOfWeek,
        boolean closed,

        @JsonFormat(pattern = "HH:mm")
        LocalTime openTime,

        @JsonFormat(pattern = "HH:mm")
        LocalTime closeTime
) {

    public static HospitalBusinessHourResponse from(
            DayOfWeek dayOfWeek,
            DailyOperatingHours operatingHours
    ) {
        if (operatingHours == null) {
            return new HospitalBusinessHourResponse(
                    dayOfWeek,
                    true,
                    null,
                    null
            );
        }

        return new HospitalBusinessHourResponse(
                dayOfWeek,
                false,
                operatingHours.openTime(),
                operatingHours.closeTime()
        );
    }
}
