package com.doctorpet.domain.hospital.scheduler;

public record HospitalSlotGenerationSummary(
        boolean locked,
        int targetDates,
        int targetTasks,
        int succeededTasks,
        int failedTasks,
        int createdSlots
) {

    public static HospitalSlotGenerationSummary lockSkipped(int targetDates) {
        return new HospitalSlotGenerationSummary(
                false,
                targetDates,
                0,
                0,
                0,
                0
        );
    }
}
