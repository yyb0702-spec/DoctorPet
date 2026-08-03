package com.doctorpet.domain.ai.model;

/** AI 상담 문장에서 허용된 병원 검색 조건만 표현한다. */
public record AiHospitalSearchIntent(
        boolean openNow,
        boolean nightCare,
        boolean distance
) {

    public AiHospitalSearchIntent withEmergencyVisit() {
        return new AiHospitalSearchIntent(true, nightCare, distance);
    }
}
