package com.doctorpet.domain.reservation.dto.query;

import com.doctorpet.domain.reservation.dto.response.ReservationHistoryResponse;

public record ReservationHistoryAggregate(
        Long memberId,
        Long totalReservationCount,
        Long completedCount,
        Long cancelCount,
        Long noShowCount
) {

    public ReservationHistoryResponse toResponse() {
        return new ReservationHistoryResponse(
                valueOrZero(totalReservationCount),
                valueOrZero(completedCount),
                valueOrZero(cancelCount),
                valueOrZero(noShowCount)
        );
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
