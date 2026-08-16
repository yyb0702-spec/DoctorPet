package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.doctorpet.domain.hospital.dto.query.HospitalResponseMetrics;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationResponseMetricsProjection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalResponseMetricsServiceTest {

    private static final Long HOSPITAL_ID = 1L;
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationResponseMetricsProjection aggregate;

    private HospitalResponseMetricsService service;

    @BeforeEach
    void setUp() {
        service = new HospitalResponseMetricsService(
                reservationRepository,
                Clock.fixed(NOW, SEOUL_ZONE)
        );
    }

    @Test
    @DisplayName("최근 90일 집계를 정수 응답률과 평균 승인 분으로 반올림한다")
    void getMetrics_roundsAggregatesAndUsesNinetyDayWindow() {
        LocalDateTime to = LocalDateTime.of(2026, 8, 14, 15, 0);
        LocalDateTime from = to.minusDays(90);
        given(reservationRepository.findResponseMetricsAggregate(HOSPITAL_ID, from, to))
                .willReturn(aggregate);
        given(aggregate.getResponseSampleCount()).willReturn(12L);
        given(aggregate.getRespondedCount()).willReturn(10L);
        given(aggregate.getApprovedCount()).willReturn(10L);
        given(aggregate.getAverageApprovalSeconds()).willReturn(new BigDecimal("95"));

        HospitalResponseMetrics result = service.getMetrics(HOSPITAL_ID);

        assertThat(result.reservationResponseRate()).isEqualTo(83);
        assertThat(result.averageApprovalMinutes()).isEqualTo(2);
        then(reservationRepository).should()
                .findResponseMetricsAggregate(HOSPITAL_ID, from, to);
    }

    @Test
    @DisplayName("응답 표본은 충분하지만 승인 표본이 부족하면 평균 승인 시간만 숨긴다")
    void getMetrics_insufficientApprovalSamples_hidesOnlyApprovalMinutes() {
        givenAggregateLookup();
        given(aggregate.getResponseSampleCount()).willReturn(10L);
        given(aggregate.getRespondedCount()).willReturn(7L);
        given(aggregate.getApprovedCount()).willReturn(9L);

        HospitalResponseMetrics result = service.getMetrics(HOSPITAL_ID);

        assertThat(result.reservationResponseRate()).isEqualTo(70);
        assertThat(result.averageApprovalMinutes()).isNull();
    }

    @Test
    @DisplayName("응답 표본이 10건 미만이면 두 지표를 공개하지 않는다")
    void getMetrics_insufficientResponseSamples_returnsUnavailableMetrics() {
        givenAggregateLookup();
        given(aggregate.getResponseSampleCount()).willReturn(9L);
        given(aggregate.getApprovedCount()).willReturn(9L);

        HospitalResponseMetrics result = service.getMetrics(HOSPITAL_ID);

        assertThat(result).isEqualTo(HospitalResponseMetrics.unavailable());
    }

    private void givenAggregateLookup() {
        LocalDateTime to = LocalDateTime.of(2026, 8, 14, 15, 0);
        given(reservationRepository.findResponseMetricsAggregate(
                HOSPITAL_ID,
                to.minusDays(90),
                to
        )).willReturn(aggregate);
    }
}
