package com.doctorpet.domain.review.dto.response;

import java.math.BigDecimal;

public record ReviewRatingSummary(
        BigDecimal averageRating,
        long reviewCount
) {

    public static ReviewRatingSummary empty() {
        return new ReviewRatingSummary(null, 0L);
    }
}
