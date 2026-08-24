package com.doctorpet.domain.hospital.dto.response;

import java.time.LocalDate;

public record HospitalDateAvailabilityResponse(
        LocalDate date,
        boolean reservationAvailable
) {
}
