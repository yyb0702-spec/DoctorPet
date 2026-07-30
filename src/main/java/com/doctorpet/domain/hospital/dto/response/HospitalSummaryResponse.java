package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.Hospital;

public record HospitalSummaryResponse(
        Long hospitalId,
        String name
) {

    public static HospitalSummaryResponse from(Hospital hospital) {
        return new HospitalSummaryResponse(
                hospital.getId(),
                hospital.getName()
        );
    }
}
