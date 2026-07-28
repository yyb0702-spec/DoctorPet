package com.doctorpet.domain.hospital.dto.query;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalDetail;

public record HospitalSearchCandidate(
        Hospital hospital,
        HospitalDetail detail
) {
}
