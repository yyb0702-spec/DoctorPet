package com.doctorpet.domain.review.dto.response;

import com.doctorpet.domain.review.entity.Review;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReviewResponse(
        Long reviewId,
        Long reservationId,
        Long hospitalId,
        Long memberId,
        BigDecimal rating,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReviewResponse from(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getReservationId(),
                review.getHospitalId(),
                review.getMemberId(),
                review.getRating(),
                review.getContent(),
                review.getCreatedAt(),
                review.getUpdatedAt()
        );
    }
}
