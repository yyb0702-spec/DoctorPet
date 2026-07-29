package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;

import java.math.BigDecimal;

public record HospitalSearchResponse(
        Long hospitalId,
        String name,
        String address,
        BigDecimal distanceKm,
        BusinessStatus businessStatus,
        PartnershipStatus partnershipStatus,
        boolean reservationAvailable,
        String partnershipBadge,
        Boolean openNow
) {

    public static HospitalSearchResponse from(
            Hospital hospital,
            BigDecimal distanceKm,
            Boolean openNow
    ) {
        boolean reservationAvailable =
                hospital.getPartnershipStatus() == PartnershipStatus.PARTNER
                        && hospital.getBusinessStatus() == BusinessStatus.OPEN;

        return new HospitalSearchResponse(
                hospital.getId(),
                hospital.getName(),
                resolveAddress(hospital),
                distanceKm,
                hospital.getBusinessStatus(),
                hospital.getPartnershipStatus(),
                reservationAvailable,
                hospital.getPartnershipStatus() == PartnershipStatus.NON_PARTNER
                        ? "제휴 전 병원"
                        : null,
                hospital.getPartnershipStatus() == PartnershipStatus.PARTNER
                        ? openNow
                        : null
        );
    }

    private static String resolveAddress(Hospital hospital) {
        if (hospital.getAddressRoad() != null
                && !hospital.getAddressRoad().isBlank()) {
            return hospital.getAddressRoad();
        }

        return hospital.getAddressJibun();
    }
}
