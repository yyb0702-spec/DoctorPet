package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalFavorite;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import java.time.LocalDateTime;

public record FavoriteHospitalResponse(
        Long hospitalId,
        String name,
        String address,
        BusinessStatus businessStatus,
        PartnershipStatus partnershipStatus,
        boolean favorite,
        LocalDateTime favoritedAt
) {

    public static FavoriteHospitalResponse from(
            HospitalFavorite favorite
    ) {
        Hospital hospital = favorite.getHospital();

        return new FavoriteHospitalResponse(
                hospital.getId(),
                hospital.getName(),
                resolveAddress(hospital),
                hospital.getBusinessStatus(),
                hospital.getPartnershipStatus(),
                true,
                favorite.getCreatedAt()
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
