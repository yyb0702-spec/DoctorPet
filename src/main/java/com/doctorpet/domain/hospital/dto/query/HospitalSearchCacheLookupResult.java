package com.doctorpet.domain.hospital.dto.query;

import java.util.Optional;

public record HospitalSearchCacheLookupResult(
        HospitalSearchCacheLookupStatus status,
        HospitalSearchCachedPage cachedPage
) {

    public static HospitalSearchCacheLookupResult hit(
            HospitalSearchCachedPage cachedPage
    ) {
        return new HospitalSearchCacheLookupResult(
                HospitalSearchCacheLookupStatus.HIT,
                cachedPage
        );
    }

    public static HospitalSearchCacheLookupResult miss() {
        return new HospitalSearchCacheLookupResult(
                HospitalSearchCacheLookupStatus.MISS,
                null
        );
    }

    public static HospitalSearchCacheLookupResult unavailable() {
        return new HospitalSearchCacheLookupResult(
                HospitalSearchCacheLookupStatus.UNAVAILABLE,
                null
        );
    }

    public Optional<HospitalSearchCachedPage> cachedPageOptional() {
        return Optional.ofNullable(cachedPage);
    }

    public boolean canWrite() {
        return status != HospitalSearchCacheLookupStatus.UNAVAILABLE;
    }
}
