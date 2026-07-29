package com.doctorpet.domain.hospital.dto.query;

import java.util.Optional;

public record HospitalSearchCacheLookupResult(
        Status status,
        HospitalSearchCachedPage cachedPage
) {

    public enum Status {
        HIT,
        MISS,
        UNAVAILABLE
    }

    public static HospitalSearchCacheLookupResult hit(
            HospitalSearchCachedPage cachedPage
    ) {
        return new HospitalSearchCacheLookupResult(
                Status.HIT,
                cachedPage
        );
    }

    public static HospitalSearchCacheLookupResult miss() {
        return new HospitalSearchCacheLookupResult(
                Status.MISS,
                null
        );
    }

    public static HospitalSearchCacheLookupResult unavailable() {
        return new HospitalSearchCacheLookupResult(
                Status.UNAVAILABLE,
                null
        );
    }

    public Optional<HospitalSearchCachedPage> cachedPageOptional() {
        return Optional.ofNullable(cachedPage);
    }

    public boolean canWrite() {
        return status != Status.UNAVAILABLE;
    }
}
