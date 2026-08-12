package com.doctorpet.domain.hospital.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record TemporaryClosureCreateRequest(
        @NotNull LocalDate businessDate
) {
}
