package com.doctorpet.domain.review.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record ReviewPageResponse(
        List<HospitalReviewItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static ReviewPageResponse from(Page<HospitalReviewItemResponse> result) {
        return new ReviewPageResponse(
                result.getContent(),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast()
        );
    }
}
