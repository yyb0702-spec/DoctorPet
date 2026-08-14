package com.doctorpet.domain.review.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record HospitalReviewEvidence(
        Long hospitalId,
        BigDecimal averageRating,
        long reviewCount,
        long positiveReviewCount,
        long neutralReviewCount,
        long negativeReviewCount,
        List<ReviewExcerpt> excerpts
) {

    public HospitalReviewEvidence {
        excerpts = List.copyOf(excerpts);
    }

    public static HospitalReviewEvidence empty(Long hospitalId) {
        return new HospitalReviewEvidence(
                hospitalId,
                null,
                0L,
                0L,
                0L,
                0L,
                List.of()
        );
    }
}
