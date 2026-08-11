package com.doctorpet.domain.hospital.scheduler;

public record HospitalSlotGenerationSummary(
        int targetHospitals,
        int succeededHospitals,
        int failedHospitals,
        int createdSlots
) {
}
