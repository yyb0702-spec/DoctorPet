package com.doctorpet.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.review.dto.response.HospitalReviewEvidence;
import com.doctorpet.domain.review.dto.response.ReviewExcerpt;
import com.doctorpet.domain.review.model.ReviewRatingBand;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiReviewInputBudgetTest {

    @Test
    void limitsExcerptsToOnePerRatingBandAndThreePerHospital() {
        AiReviewInputBudget budget = new AiReviewInputBudget();
        HospitalReviewEvidence evidence = evidence(1L, List.of(
                excerpt(1L, ReviewRatingBand.POSITIVE, "positive latest"),
                excerpt(2L, ReviewRatingBand.POSITIVE, "positive older"),
                excerpt(3L, ReviewRatingBand.NEUTRAL, "neutral latest"),
                excerpt(4L, ReviewRatingBand.NEGATIVE, "negative latest")
        ));

        HospitalReviewEvidence limited = budget.limit(evidence);

        assertThat(limited.excerpts())
                .extracting(ReviewExcerpt::reviewId)
                .containsExactly(1L, 3L, 4L);
        assertThat(limited.reviewCount()).isEqualTo(evidence.reviewCount());
    }

    @Test
    void limitsRequestToSixtyExcerptsAndEighteenThousandCharacters() {
        AiReviewInputBudget budget = new AiReviewInputBudget();
        List<HospitalReviewEvidence> limited = new ArrayList<>();

        for (long hospitalId = 1L; hospitalId <= 21L; hospitalId++) {
            limited.add(budget.limit(evidence(hospitalId, List.of(
                    excerpt(hospitalId * 10, ReviewRatingBand.POSITIVE, "p".repeat(300)),
                    excerpt(hospitalId * 10 + 1, ReviewRatingBand.NEUTRAL, "m".repeat(300)),
                    excerpt(hospitalId * 10 + 2, ReviewRatingBand.NEGATIVE, "n".repeat(300))
            ))));
        }

        assertThat(limited.stream().mapToInt(item -> item.excerpts().size()).sum())
                .isEqualTo(AiReviewInputBudget.MAX_EXCERPTS_TOTAL);
        assertThat(limited.stream()
                .flatMap(item -> item.excerpts().stream())
                .mapToInt(item -> item.content().length())
                .sum()).isEqualTo(AiReviewInputBudget.MAX_CONTENT_LENGTH_TOTAL);
        assertThat(limited.get(20).excerpts()).isEmpty();
    }

    @Test
    void excludesLegacyReviewContentOverThreeHundredCharacters() {
        AiReviewInputBudget budget = new AiReviewInputBudget();

        HospitalReviewEvidence limited = budget.limit(evidence(1L, List.of(
                excerpt(1L, ReviewRatingBand.POSITIVE, "x".repeat(301)),
                excerpt(2L, ReviewRatingBand.NEUTRAL, "valid")
        )));

        assertThat(limited.excerpts())
                .extracting(ReviewExcerpt::reviewId)
                .containsExactly(2L);
    }

    private HospitalReviewEvidence evidence(Long hospitalId, List<ReviewExcerpt> excerpts) {
        return new HospitalReviewEvidence(
                hospitalId,
                new BigDecimal("4.0"),
                100L,
                50L,
                30L,
                20L,
                excerpts
        );
    }

    private ReviewExcerpt excerpt(Long reviewId, ReviewRatingBand band, String content) {
        BigDecimal rating = switch (band) {
            case POSITIVE -> new BigDecimal("5.0");
            case NEUTRAL -> new BigDecimal("3.5");
            case NEGATIVE -> new BigDecimal("2.0");
        };
        return new ReviewExcerpt(
                reviewId,
                rating,
                band,
                content,
                LocalDateTime.of(2026, 8, 13, 10, 0)
        );
    }
}
