package com.doctorpet.domain.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.review.entity.Review;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
}
