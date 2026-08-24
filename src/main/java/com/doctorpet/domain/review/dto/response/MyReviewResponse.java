package com.doctorpet.domain.review.dto.response;

import com.doctorpet.domain.review.entity.Review;

public record MyReviewResponse(
        ReviewResponse review,
        boolean reviewable
) {

    public static MyReviewResponse reviewed(Review review) {
        return new MyReviewResponse(ReviewResponse.from(review), false);
    }

    public static MyReviewResponse notReviewed(boolean reviewable) {
        return new MyReviewResponse(null, reviewable);
    }
}
