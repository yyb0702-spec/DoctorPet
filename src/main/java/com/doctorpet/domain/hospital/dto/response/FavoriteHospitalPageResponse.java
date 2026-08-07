package com.doctorpet.domain.hospital.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record FavoriteHospitalPageResponse(
        List<FavoriteHospitalResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static FavoriteHospitalPageResponse from(
            Page<FavoriteHospitalResponse> result
    ) {
        return new FavoriteHospitalPageResponse(
                List.copyOf(result.getContent()),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast()
        );
    }
}
