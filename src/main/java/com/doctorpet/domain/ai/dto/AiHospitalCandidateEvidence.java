package com.doctorpet.domain.ai.dto;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.review.dto.response.HospitalReviewEvidence;
import java.util.List;

public record AiHospitalCandidateEvidence(
        HospitalSearchResponse hospital,
        List<CapabilityValue> supportedSpecies,
        List<CapabilityValue> capabilities,
        HospitalReviewEvidence reviews
) {

    public AiHospitalCandidateEvidence {
        supportedSpecies = List.copyOf(supportedSpecies);
        capabilities = List.copyOf(capabilities);
    }
}
