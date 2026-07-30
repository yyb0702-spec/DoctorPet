package com.doctorpet.domain.hospital.dto.response;

import java.util.List;

public record HospitalSearchPageResponse(
        List<HospitalSearchResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static HospitalSearchPageResponse of(
            List<HospitalSearchResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        return new HospitalSearchPageResponse(
                List.copyOf(content),
                page,
                size,
                totalElements,
                totalPages,
                page == 1,
                totalPages == 0 || page >= totalPages
        );
    }
}
