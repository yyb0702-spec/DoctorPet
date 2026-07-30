package com.doctorpet.domain.hospital.dto.query;

import java.math.BigDecimal;

public record HospitalSearchResult(
        HospitalSearchCandidate candidate,
        BigDecimal preciseDistanceKm,
        Boolean openNow
) {
}
