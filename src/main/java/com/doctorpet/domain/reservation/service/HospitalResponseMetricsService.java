package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.hospital.dto.query.HospitalResponseMetrics;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationResponseMetricsProjection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalResponseMetricsService {

    private static final int LOOKBACK_DAYS = 90;
    private static final long MINIMUM_SAMPLE_COUNT = 10L;
    private static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100L);
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60L);

    private final ReservationRepository reservationRepository;
    private final Clock clock;

    public HospitalResponseMetrics getMetrics(Long hospitalId) {
        LocalDateTime to = LocalDateTime.now(clock);
        LocalDateTime from = to.minusDays(LOOKBACK_DAYS);
        ReservationResponseMetricsProjection aggregate = reservationRepository
                .findResponseMetricsAggregate(hospitalId, from, to);

        return new HospitalResponseMetrics(
                calculateResponseRate(aggregate),
                calculateAverageApprovalMinutes(aggregate)
        );
    }

    private Integer calculateResponseRate(ReservationResponseMetricsProjection aggregate) {
        if (aggregate.getResponseSampleCount() < MINIMUM_SAMPLE_COUNT) {
            return null;
        }
        return BigDecimal.valueOf(aggregate.getRespondedCount())
                .multiply(PERCENT_MULTIPLIER)
                .divide(
                        BigDecimal.valueOf(aggregate.getResponseSampleCount()),
                        0,
                        RoundingMode.HALF_UP
                )
                .intValueExact();
    }

    private Integer calculateAverageApprovalMinutes(
            ReservationResponseMetricsProjection aggregate
    ) {
        if (aggregate.getApprovedCount() < MINIMUM_SAMPLE_COUNT
                || aggregate.getAverageApprovalSeconds() == null) {
            return null;
        }
        return aggregate.getAverageApprovalSeconds()
                .divide(SECONDS_PER_MINUTE, 0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
