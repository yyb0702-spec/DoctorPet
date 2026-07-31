package com.doctorpet.domain.reservation.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record HospitalReservationPageResponse(
        List<HospitalReservationListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static HospitalReservationPageResponse from(
            Page<HospitalReservationListItemResponse> result
    ) {
        return new HospitalReservationPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast()
        );
    }
}
