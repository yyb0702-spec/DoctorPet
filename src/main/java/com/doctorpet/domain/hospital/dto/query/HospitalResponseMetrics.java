package com.doctorpet.domain.hospital.dto.query;

public record HospitalResponseMetrics(
        Integer reservationResponseRate,
        Integer averageApprovalMinutes
) {

    public static HospitalResponseMetrics unavailable() {
        return new HospitalResponseMetrics(null, null);
    }
}
