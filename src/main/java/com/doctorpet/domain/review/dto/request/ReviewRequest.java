package com.doctorpet.domain.review.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ReviewRequest(
        @NotNull
        @DecimalMin("1.0")
        @DecimalMax("5.0")
        BigDecimal rating,

        @NotBlank
        String content
) {

    private static final BigDecimal RATING_STEP = new BigDecimal("0.5");

    @AssertTrue(message = "평점은 0.5 단위여야 합니다.")
    public boolean isRatingStepValid() {
        return rating == null || rating.remainder(RATING_STEP).compareTo(BigDecimal.ZERO) == 0;
    }
}
