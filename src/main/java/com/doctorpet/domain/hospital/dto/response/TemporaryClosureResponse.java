package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import java.time.LocalDate;

public record TemporaryClosureResponse(
        LocalDate businessDate
) {

    public static TemporaryClosureResponse from(HospitalTemporaryClosure closure) {
        return new TemporaryClosureResponse(closure.getBusinessDate());
    }
}
