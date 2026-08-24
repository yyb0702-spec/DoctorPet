package com.doctorpet.domain.review.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface ReviewExcerptProjection {

    Long getReviewId();

    Long getHospitalId();

    BigDecimal getRating();

    String getContent();

    LocalDateTime getCreatedAt();
}
