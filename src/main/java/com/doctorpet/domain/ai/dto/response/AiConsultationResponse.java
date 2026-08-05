package com.doctorpet.domain.ai.dto.response;

import com.doctorpet.domain.ai.model.AiStructuredResult;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import java.util.List;

public record AiConsultationResponse(
        AiStructuredResult structured,
        List<HospitalSearchResponse> hospitals,
        String disclaimer,
        String message,
        boolean fallback,
        boolean locationRequired,
        boolean locationRecommended
) {

    public AiConsultationResponse {
        hospitals = List.copyOf(hospitals);
    }

    public AiConsultationResponse(
            AiStructuredResult structured,
            List<HospitalSearchResponse> hospitals,
            String disclaimer,
            String message,
            boolean fallback,
            boolean locationRecommended
    ) {
        this(
                structured,
                hospitals,
                disclaimer,
                message,
                fallback,
                false,
                locationRecommended
        );
    }
}
