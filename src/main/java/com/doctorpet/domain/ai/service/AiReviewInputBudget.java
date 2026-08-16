package com.doctorpet.domain.ai.service;

import com.doctorpet.domain.review.dto.response.HospitalReviewEvidence;
import com.doctorpet.domain.review.dto.response.ReviewExcerpt;
import com.doctorpet.domain.review.model.ReviewRatingBand;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

final class AiReviewInputBudget {

    static final int MAX_EXCERPTS_PER_HOSPITAL = 3;
    static final int MAX_EXCERPTS_TOTAL = 60;
    static final int MAX_CONTENT_LENGTH = 300;
    static final int MAX_CONTENT_LENGTH_TOTAL = 18_000;

    private int excerptCount;
    private int contentLength;

    HospitalReviewEvidence limit(HospitalReviewEvidence evidence) {
        EnumSet<ReviewRatingBand> selectedBands = EnumSet.noneOf(ReviewRatingBand.class);
        List<ReviewExcerpt> excerpts = new ArrayList<>();
        for (ReviewExcerpt excerpt : evidence.excerpts()) {
            if (excerpts.size() >= MAX_EXCERPTS_PER_HOSPITAL
                    || excerptCount >= MAX_EXCERPTS_TOTAL) {
                break;
            }
            String content = excerpt.content();
            if (content == null
                    || content.length() > MAX_CONTENT_LENGTH
                    || !selectedBands.add(excerpt.ratingBand())
                    || contentLength + content.length() > MAX_CONTENT_LENGTH_TOTAL) {
                continue;
            }
            excerpts.add(excerpt);
            excerptCount++;
            contentLength += content.length();
        }
        return new HospitalReviewEvidence(
                evidence.hospitalId(),
                evidence.averageRating(),
                evidence.reviewCount(),
                evidence.positiveReviewCount(),
                evidence.neutralReviewCount(),
                evidence.negativeReviewCount(),
                excerpts
        );
    }
}
