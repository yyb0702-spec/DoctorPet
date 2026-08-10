package com.doctorpet.domain.review.dto.response;

import com.doctorpet.domain.review.entity.Review;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record HospitalReviewItemResponse(
        Long reviewId,
        BigDecimal rating,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static HospitalReviewItemResponse from(Review review) {
        return new HospitalReviewItemResponse(
                review.getId(),
                review.getRating(),
                review.getContent(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
