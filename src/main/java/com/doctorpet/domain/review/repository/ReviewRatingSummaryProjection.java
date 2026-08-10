package com.doctorpet.domain.review.repository;

import java.math.BigDecimal;

public interface ReviewRatingSummaryProjection {

    BigDecimal getAverageRating();

    long getReviewCount();
}
