package com.doctorpet.domain.review.dto.response;

import com.doctorpet.domain.review.model.ReviewRatingBand;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReviewExcerpt(
        Long reviewId,
        BigDecimal rating,
        ReviewRatingBand ratingBand,
        String content,
        LocalDateTime createdAt
) {
}
