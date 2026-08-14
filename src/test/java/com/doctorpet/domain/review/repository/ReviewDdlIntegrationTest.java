package com.doctorpet.domain.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.review.entity.Review;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class ReviewDdlIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("리뷰의 예약·병원·회원·평점·내용이 실제 MySQL에 영속화된다")
    void review_persistsCorrectly() {
        Review saved = reviewRepository.saveAndFlush(Review.create(
                91001L,
                92001L,
                93001L,
                new BigDecimal("4.5"),
                "친절했어요."
        ));
        entityManager.clear();

        Review reloaded = reviewRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getReservationId()).isEqualTo(91001L);
        assertThat(reloaded.getRating()).isEqualByComparingTo("4.5");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 예약에 리뷰를 두 번 저장하면 UNIQUE 제약에 걸린다")
    void duplicateReservationId_violatesUnique() {
        reviewRepository.saveAndFlush(Review.create(
                91002L, 92001L, 93001L, new BigDecimal("4.0"), "첫 리뷰"
        ));

        assertThatThrownBy(() -> reviewRepository.saveAndFlush(Review.create(
                91002L, 92001L, 93001L, new BigDecimal("5.0"), "두 번째 리뷰"
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("0.5 단위가 아닌 평점은 CHECK 제약에 걸린다")
    void invalidRatingStep_violatesCheck() {
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(Review.create(
                91003L, 92001L, 93001L, new BigDecimal("4.3"), "잘못된 평점"
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("병원 리뷰의 평균 평점과 개수를 한 번에 집계한다")
    void ratingSummary_aggregatesAverageAndCount() {
        reviewRepository.saveAndFlush(Review.create(
                91004L, 92002L, 93001L, new BigDecimal("4.0"), "첫 리뷰"
        ));
        reviewRepository.saveAndFlush(Review.create(
                91005L, 92002L, 93002L, new BigDecimal("4.5"), "두 번째 리뷰"
        ));

        ReviewRatingSummaryProjection summary =
                reviewRepository.findRatingSummaryByHospitalId(92002L);

        assertThat(summary.getAverageRating()).isEqualByComparingTo("4.25");
        assertThat(summary.getReviewCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("리뷰가 없는 병원의 평균은 null이고 개수는 0이다")
    void ratingSummary_withoutReviews_returnsNullAndZero() {
        ReviewRatingSummaryProjection summary =
                reviewRepository.findRatingSummaryByHospitalId(92999L);

        assertThat(summary.getAverageRating()).isNull();
        assertThat(summary.getReviewCount()).isZero();
    }

    @Test
    @DisplayName("후보 병원별 리뷰 통계와 평점 구간별 최신 1개를 배치 조회한다")
    void reviewEvidence_queriesStatisticsAndLatestExcerptsInBatch() {
        long firstHospitalId = 92101L;
        long secondHospitalId = 92102L;
        saveReview(91101L, firstHospitalId, 93101L, "5.0", "긍정 과거");
        saveReview(91102L, firstHospitalId, 93102L, "4.5", "긍정 중간");
        saveReview(91103L, firstHospitalId, 93103L, "4.0", "긍정 최신");
        saveReview(91104L, firstHospitalId, 93104L, "3.5", "보통 과거");
        saveReview(91105L, firstHospitalId, 93105L, "3.0", "보통 중간");
        saveReview(91106L, firstHospitalId, 93106L, "3.5", "보통 최신");
        saveReview(91107L, firstHospitalId, 93107L, "2.5", "부정 과거");
        saveReview(91108L, firstHospitalId, 93108L, "2.0", "부정 중간");
        saveReview(91109L, firstHospitalId, 93109L, "1.0", "부정 최신");
        saveReview(91110L, secondHospitalId, 93110L, "5.0", "다른 병원 리뷰");
        saveReview(91111L, firstHospitalId, 93111L, "5.0", "가".repeat(301));

        List<HospitalReviewStatisticsProjection> statistics =
                reviewRepository.findStatisticsByHospitalIds(
                        List.of(firstHospitalId, secondHospitalId));
        List<ReviewExcerptProjection> excerpts =
                reviewRepository.findLatestExcerptsByHospitalIds(
                        List.of(firstHospitalId, secondHospitalId));

        HospitalReviewStatisticsProjection firstStatistics = statistics.stream()
                .filter(item -> item.getHospitalId().equals(firstHospitalId))
                .findFirst()
                .orElseThrow();
        assertThat(firstStatistics.getReviewCount()).isEqualTo(10L);
        assertThat(firstStatistics.getPositiveReviewCount()).isEqualTo(4L);
        assertThat(firstStatistics.getNeutralReviewCount()).isEqualTo(3L);
        assertThat(firstStatistics.getNegativeReviewCount()).isEqualTo(3L);
        assertThat(excerpts.stream()
                .filter(item -> item.getHospitalId().equals(firstHospitalId))
                .map(ReviewExcerptProjection::getContent))
                .containsExactlyInAnyOrder(
                        "긍정 최신",
                        "보통 최신",
                        "부정 최신"
                );
        assertThat(excerpts)
                .noneMatch(item -> item.getContent().length() > 300);
        assertThat(excerpts.stream()
                .filter(item -> item.getHospitalId().equals(secondHospitalId)))
                .singleElement()
                .satisfies(item -> assertThat(item.getContent()).isEqualTo("다른 병원 리뷰"));
    }

    private void saveReview(
            Long reservationId,
            Long hospitalId,
            Long memberId,
            String rating,
            String content
    ) {
        reviewRepository.saveAndFlush(Review.create(
                reservationId,
                hospitalId,
                memberId,
                new BigDecimal(rating),
                content
        ));
    }
}
