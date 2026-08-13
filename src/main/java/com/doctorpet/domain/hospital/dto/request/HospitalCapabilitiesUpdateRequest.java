package com.doctorpet.domain.hospital.dto.request;

import com.doctorpet.domain.hospital.entity.CapabilityValue;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record HospitalCapabilitiesUpdateRequest(
        @NotNull List<@NotNull CapabilityValue> capabilities
) {
}
