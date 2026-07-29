package com.doctorpet.domain.hospital.dto.query;

import com.doctorpet.domain.hospital.entity.CapabilityValue;

import java.math.BigDecimal;
import java.util.List;

public record HospitalSearchCondition(
        String keyword,
        String region,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal radiusKm,
        List<CapabilityValue> requiredCapabilities,
        List<CapabilityValue> supportedSpecies,
        Boolean surgery,
        Boolean hospitalization,
        Boolean nightCare,
        Boolean emergency,
        boolean partnerOnly
) {
}
