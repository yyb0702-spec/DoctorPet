package com.doctorpet.domain.hospital.partnership.dto;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * 공공데이터에 없는 제휴 병원의 운영·시설 더미 데이터를 표현합니다.
 */
public record PartnerHospitalDetailSeedData(
        Map<DayOfWeek, PartnerHospitalOperatingHoursSeedData> openHours,
        boolean surgeryAvailable,
        boolean hospitalizationAvailable,
        boolean nightCare,
        boolean emergency
) {
}
