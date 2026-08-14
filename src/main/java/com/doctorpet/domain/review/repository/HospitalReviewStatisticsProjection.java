package com.doctorpet.domain.review.repository;

import java.math.BigDecimal;

public interface HospitalReviewStatisticsProjection {

    Long getHospitalId();

    BigDecimal getAverageRating();

    long getReviewCount();

    long getPositiveReviewCount();

    long getNeutralReviewCount();

    long getNegativeReviewCount();
}
