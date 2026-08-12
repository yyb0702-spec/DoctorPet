package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import java.util.Comparator;
import java.util.List;

public record HospitalCapabilitiesResponse(
        List<CapabilityValue> capabilities
) {

    public static HospitalCapabilitiesResponse from(
            List<HospitalCapability> hospitalCapabilities
    ) {
        List<CapabilityValue> capabilities = hospitalCapabilities.stream()
                .map(HospitalCapability::getCapabilityValue)
                .sorted(Comparator.naturalOrder())
                .toList();

        return new HospitalCapabilitiesResponse(capabilities);
    }
}
