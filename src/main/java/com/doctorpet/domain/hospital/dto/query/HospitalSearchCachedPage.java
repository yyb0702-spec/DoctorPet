package com.doctorpet.domain.hospital.dto.query;

import java.util.List;

public record HospitalSearchCachedPage(
        List<HospitalSearchCandidate> content,
        long totalElements
) {
}
