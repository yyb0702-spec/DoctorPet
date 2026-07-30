package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
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
            HospitalSearchCandidate candidate,
            BigDecimal distanceKm,
            Boolean openNow
    ) {
        boolean reservationAvailable =
                candidate.partnershipStatus() == PartnershipStatus.PARTNER
                        && candidate.businessStatus() == BusinessStatus.OPEN;

        return new HospitalSearchResponse(
                candidate.hospitalId(),
                candidate.name(),
                resolveAddress(candidate),
                distanceKm,
                candidate.businessStatus(),
                candidate.partnershipStatus(),
                reservationAvailable,
                candidate.partnershipStatus() == PartnershipStatus.NON_PARTNER
                        ? "제휴 전 병원"
                        : null,
                candidate.partnershipStatus() == PartnershipStatus.PARTNER
                        ? openNow
                        : null
        );
    }

    private static String resolveAddress(HospitalSearchCandidate candidate) {
        if (candidate.addressRoad() != null
                && !candidate.addressRoad().isBlank()) {
            return candidate.addressRoad();
        }

        return candidate.addressJibun();
    }
}
