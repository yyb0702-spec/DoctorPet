package com.doctorpet.domain.hospital.support;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import java.util.List;

public final class HospitalDetailTestFixture {

    private HospitalDetailTestFixture() {
    }

    public static HospitalDetailResponse partnerHospital(Long hospitalId, String name) {
        return new HospitalDetailResponse(
                hospitalId,
                name,
                null,
                null,
                BusinessStatus.OPEN,
                PartnershipStatus.PARTNER,
                null,
                true,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                0L,
                false,
                null,
                null
        );
    }
}
