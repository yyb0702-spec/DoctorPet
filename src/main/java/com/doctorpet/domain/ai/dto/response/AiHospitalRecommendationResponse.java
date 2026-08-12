package com.doctorpet.domain.ai.dto.response;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import java.util.List;

public record AiHospitalRecommendationResponse(
        HospitalSearchResponse hospital,
        int recommendationScore,
        String recommendationReason,
        List<String> evidence
) {

    public AiHospitalRecommendationResponse {
        evidence = List.copyOf(evidence);
    }
}
