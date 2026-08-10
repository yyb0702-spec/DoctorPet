package com.doctorpet.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.doctorpet.domain.review.dto.response.ReviewRatingSummary;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.review.repository.ReviewRatingSummaryProjection;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewQueryServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Test
    void 평균_평점을_소수점_첫째_자리로_반올림한다() {
        ReviewRatingSummaryProjection projection =
                mock(ReviewRatingSummaryProjection.class);
        given(projection.getAverageRating()).willReturn(new BigDecimal("4.25"));
        given(projection.getReviewCount()).willReturn(2L);
        given(reviewRepository.findRatingSummaryByHospitalId(1L))
                .willReturn(projection);
        ReviewQueryService service = new ReviewQueryService(reviewRepository);

        ReviewRatingSummary summary = service.getRatingSummary(1L);

        assertThat(summary.averageRating()).isEqualByComparingTo("4.3");
        assertThat(summary.reviewCount()).isEqualTo(2L);
    }

    @Test
    void 리뷰가_없으면_평균은_null이고_개수는_0이다() {
        ReviewRatingSummaryProjection projection =
                mock(ReviewRatingSummaryProjection.class);
        given(projection.getReviewCount()).willReturn(0L);
        given(reviewRepository.findRatingSummaryByHospitalId(1L))
                .willReturn(projection);
        ReviewQueryService service = new ReviewQueryService(reviewRepository);

        ReviewRatingSummary summary = service.getRatingSummary(1L);

        assertThat(summary.averageRating()).isNull();
        assertThat(summary.reviewCount()).isZero();
    }
}
