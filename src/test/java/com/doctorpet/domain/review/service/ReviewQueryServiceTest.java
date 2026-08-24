package com.doctorpet.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.review.dto.response.HospitalReviewEvidence;
import com.doctorpet.domain.review.dto.response.ReviewRatingSummary;
import com.doctorpet.domain.review.model.ReviewRatingBand;
import com.doctorpet.domain.review.repository.HospitalReviewStatisticsProjection;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.review.repository.ReviewExcerptProjection;
import com.doctorpet.domain.review.repository.ReviewRatingSummaryProjection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    @Test
    void 후보_병원들의_리뷰_근거를_배치로_조회한다() {
        HospitalReviewStatisticsProjection statistics =
                mock(HospitalReviewStatisticsProjection.class);
        given(statistics.getHospitalId()).willReturn(1L);
        given(statistics.getAverageRating()).willReturn(new BigDecimal("4.25"));
        given(statistics.getReviewCount()).willReturn(4L);
        given(statistics.getPositiveReviewCount()).willReturn(2L);
        given(statistics.getNeutralReviewCount()).willReturn(1L);
        given(statistics.getNegativeReviewCount()).willReturn(1L);

        ReviewExcerptProjection excerpt = mock(ReviewExcerptProjection.class);
        given(excerpt.getReviewId()).willReturn(10L);
        given(excerpt.getHospitalId()).willReturn(1L);
        given(excerpt.getRating()).willReturn(new BigDecimal("4.5"));
        given(excerpt.getContent()).willReturn("친절한 진료");
        given(excerpt.getCreatedAt()).willReturn(LocalDateTime.of(2026, 8, 12, 10, 0));

        given(reviewRepository.findStatisticsByHospitalIds(List.of(1L, 2L)))
                .willReturn(List.of(statistics));
        given(reviewRepository.findLatestExcerptsByHospitalIds(List.of(1L, 2L)))
                .willReturn(List.of(excerpt));
        ReviewQueryService service = new ReviewQueryService(reviewRepository);

        Map<Long, HospitalReviewEvidence> result =
                service.getEvidenceByHospitalIds(List.of(1L, 2L, 1L));

        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(1L).averageRating()).isEqualByComparingTo("4.3");
        assertThat(result.get(1L).reviewCount()).isEqualTo(4L);
        assertThat(result.get(1L).positiveReviewCount()).isEqualTo(2L);
        assertThat(result.get(1L).neutralReviewCount()).isEqualTo(1L);
        assertThat(result.get(1L).negativeReviewCount()).isEqualTo(1L);
        assertThat(result.get(1L).excerpts()).singleElement().satisfies(review -> {
            assertThat(review.reviewId()).isEqualTo(10L);
            assertThat(review.ratingBand()).isEqualTo(ReviewRatingBand.POSITIVE);
            assertThat(review.content()).isEqualTo("친절한 진료");
        });
        assertThat(result.get(2L)).isEqualTo(HospitalReviewEvidence.empty(2L));
        verify(reviewRepository).findStatisticsByHospitalIds(List.of(1L, 2L));
        verify(reviewRepository).findLatestExcerptsByHospitalIds(List.of(1L, 2L));
    }

    @Test
    void 후보_병원이_없으면_저장소를_조회하지_않는다() {
        ReviewQueryService service = new ReviewQueryService(reviewRepository);

        Map<Long, HospitalReviewEvidence> result =
                service.getEvidenceByHospitalIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(reviewRepository);
    }
}
