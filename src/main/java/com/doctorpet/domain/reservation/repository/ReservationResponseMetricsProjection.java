package com.doctorpet.domain.reservation.repository;

import java.math.BigDecimal;

public interface ReservationResponseMetricsProjection {

    long getResponseSampleCount();

    long getRespondedCount();

    long getApprovedCount();

    BigDecimal getAverageApprovalSeconds();
}
