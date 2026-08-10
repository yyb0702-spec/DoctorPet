package com.doctorpet.domain.review.service;

import com.doctorpet.domain.review.dto.response.ReviewRatingSummary;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.review.repository.ReviewRatingSummaryProjection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewQueryService {

    private final ReviewRepository reviewRepository;

    @Transactional(readOnly = true)
    public ReviewRatingSummary getRatingSummary(Long hospitalId) {
        ReviewRatingSummaryProjection result =
                reviewRepository.findRatingSummaryByHospitalId(hospitalId);
        long reviewCount = result.getReviewCount();
        if (reviewCount == 0L) {
            return ReviewRatingSummary.empty();
        }
        BigDecimal averageRating = result.getAverageRating()
                .setScale(1, RoundingMode.HALF_UP);
        return new ReviewRatingSummary(averageRating, reviewCount);
    }
}
