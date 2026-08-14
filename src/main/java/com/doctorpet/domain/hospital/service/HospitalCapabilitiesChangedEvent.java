package com.doctorpet.domain.hospital.service;

public record HospitalCapabilitiesChangedEvent(
        Long hospitalId,
        Long actorMemberId,
        int beforeCount,
        int afterCount
) {
}
