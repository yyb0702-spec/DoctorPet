package com.doctorpet.domain.ai.service;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import java.util.List;

final class AiToolExecutionState {

    private List<HospitalSearchResponse> hospitals = List.of();
    private AiAnalysisResult analysis;
    private boolean emergencySearch;

    List<HospitalSearchResponse> hospitals() {
        return hospitals;
    }

    AiAnalysisResult analysis() {
        return analysis;
    }

    boolean emergencySearch() {
        return emergencySearch;
    }

    void updateAnalysis(AiAnalysisResult analysis, boolean emergencySearch) {
        this.analysis = analysis;
        this.emergencySearch = emergencySearch;
    }

    void updateHospitals(List<HospitalSearchResponse> hospitals) {
        this.hospitals = hospitals;
    }
}
