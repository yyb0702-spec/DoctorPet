package com.doctorpet.domain.review.service;

import com.doctorpet.domain.review.dto.response.HospitalReviewEvidence;
import com.doctorpet.domain.review.dto.response.ReviewExcerpt;
import com.doctorpet.domain.review.dto.response.ReviewRatingSummary;
import com.doctorpet.domain.review.model.ReviewRatingBand;
import com.doctorpet.domain.review.repository.HospitalReviewStatisticsProjection;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.review.repository.ReviewExcerptProjection;
import com.doctorpet.domain.review.repository.ReviewRatingSummaryProjection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    @Transactional(readOnly = true)
    public Map<Long, HospitalReviewEvidence> getEvidenceByHospitalIds(
            Collection<Long> hospitalIds
    ) {
        List<Long> distinctHospitalIds = hospitalIds.stream()
                .distinct()
                .toList();
        if (distinctHospitalIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, HospitalReviewStatisticsProjection> statisticsByHospitalId =
                reviewRepository.findStatisticsByHospitalIds(distinctHospitalIds).stream()
                        .collect(Collectors.toMap(
                                HospitalReviewStatisticsProjection::getHospitalId,
                                statistics -> statistics
                        ));
        Map<Long, List<ReviewExcerpt>> excerptsByHospitalId =
                reviewRepository.findLatestExcerptsByHospitalIds(distinctHospitalIds).stream()
                        .collect(Collectors.groupingBy(
                                ReviewExcerptProjection::getHospitalId,
                                Collectors.mapping(
                                        this::toExcerpt,
                                        Collectors.toList()
                                )
                        ));

        Map<Long, HospitalReviewEvidence> evidenceByHospitalId = new LinkedHashMap<>();
        for (Long hospitalId : distinctHospitalIds) {
            HospitalReviewStatisticsProjection statistics =
                    statisticsByHospitalId.get(hospitalId);
            if (statistics == null) {
                evidenceByHospitalId.put(hospitalId, HospitalReviewEvidence.empty(hospitalId));
                continue;
            }
            evidenceByHospitalId.put(hospitalId, new HospitalReviewEvidence(
                    hospitalId,
                    statistics.getAverageRating().setScale(1, RoundingMode.HALF_UP),
                    statistics.getReviewCount(),
                    statistics.getPositiveReviewCount(),
                    statistics.getNeutralReviewCount(),
                    statistics.getNegativeReviewCount(),
                    excerptsByHospitalId.getOrDefault(hospitalId, List.of())
            ));
        }
        return Collections.unmodifiableMap(evidenceByHospitalId);
    }

    private ReviewExcerpt toExcerpt(ReviewExcerptProjection projection) {
        return new ReviewExcerpt(
                projection.getReviewId(),
                projection.getRating(),
                ReviewRatingBand.from(projection.getRating()),
                projection.getContent(),
                projection.getCreatedAt()
        );
    }
}
