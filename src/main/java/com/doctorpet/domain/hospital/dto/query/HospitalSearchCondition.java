package com.doctorpet.domain.hospital.dto.query;

import com.doctorpet.domain.hospital.entity.CapabilityValue;

import java.math.BigDecimal;
import java.util.List;

public record HospitalSearchCondition(
        String keyword,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal radiusKm,
        List<CapabilityValue> capabilities,
        boolean partnerOnly
) {
}
