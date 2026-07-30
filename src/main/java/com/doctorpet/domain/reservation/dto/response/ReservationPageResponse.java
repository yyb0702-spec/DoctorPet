package com.doctorpet.domain.reservation.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record ReservationPageResponse(
        List<ReservationListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static ReservationPageResponse from(
            Page<ReservationListItemResponse> result
    ) {
        return new ReservationPageResponse(
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
