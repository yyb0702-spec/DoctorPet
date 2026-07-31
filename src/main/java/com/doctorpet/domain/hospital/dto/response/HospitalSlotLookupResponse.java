package com.doctorpet.domain.hospital.dto.response;

import java.time.LocalDate;
import java.util.List;

public record HospitalSlotLookupResponse(
        LocalDate selectedDate,
        List<HospitalDateAvailabilityResponse> dateAvailabilities,
        List<HospitalSlotResponse> slots
) {

    public static HospitalSlotLookupResponse empty(LocalDate selectedDate) {
        return new HospitalSlotLookupResponse(
                selectedDate,
                List.of(),
                List.of()
        );
    }
}
