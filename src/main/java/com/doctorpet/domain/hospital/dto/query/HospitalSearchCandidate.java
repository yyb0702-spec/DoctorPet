package com.doctorpet.domain.hospital.dto.query;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Map;

public record HospitalSearchCandidate(
        Long hospitalId,
        String name,
        String addressRoad,
        String addressJibun,
        BigDecimal longitude,
        BigDecimal latitude,
        BusinessStatus businessStatus,
        PartnershipStatus partnershipStatus,
        Map<DayOfWeek, DailyOperatingHours> openHours
) {
}
