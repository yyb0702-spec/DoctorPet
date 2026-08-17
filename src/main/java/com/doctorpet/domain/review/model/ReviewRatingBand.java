package com.doctorpet.domain.review.model;

import java.math.BigDecimal;

public enum ReviewRatingBand {
    POSITIVE,
    NEUTRAL,
    NEGATIVE;

    private static final BigDecimal POSITIVE_MINIMUM = new BigDecimal("4.0");
    private static final BigDecimal NEUTRAL_MINIMUM = new BigDecimal("3.0");

    public static ReviewRatingBand from(BigDecimal rating) {
        if (rating.compareTo(POSITIVE_MINIMUM) >= 0) {
            return POSITIVE;
        }
        if (rating.compareTo(NEUTRAL_MINIMUM) >= 0) {
            return NEUTRAL;
        }
        return NEGATIVE;
    }
}
