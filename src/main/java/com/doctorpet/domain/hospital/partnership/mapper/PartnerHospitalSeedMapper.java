package com.doctorpet.domain.hospital.partnership.mapper;

import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalDetailSeedData;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalOperatingHoursSeedData;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * 제휴 병원 JSON의 운영시간 문자열을 도메인 운영시간으로 변환합니다.
 */
@Component
public class PartnerHospitalSeedMapper {

    public Map<DayOfWeek, DailyOperatingHours> toOpenHours(
            PartnerHospitalDetailSeedData detail
    ) {
        if (detail == null || detail.openHours() == null) {
            throw new IllegalArgumentException(
                    "제휴 병원의 운영시간이 필요합니다."
            );
        }

        EnumMap<DayOfWeek, DailyOperatingHours> openHours =
                new EnumMap<>(DayOfWeek.class);

        detail.openHours().forEach((day, hours) ->
                openHours.put(day, toDailyOperatingHours(hours))
        );
        return openHours;
    }

    private DailyOperatingHours toDailyOperatingHours(
            PartnerHospitalOperatingHoursSeedData hours
    ) {
        if (hours == null) {
            throw new IllegalArgumentException(
                    "요일별 운영시간이 필요합니다."
            );
        }

        return new DailyOperatingHours(
                LocalTime.parse(hours.openTime()),
                LocalTime.parse(hours.closeTime())
        );
    }
}
