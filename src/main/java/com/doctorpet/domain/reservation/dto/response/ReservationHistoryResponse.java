package com.doctorpet.domain.reservation.dto.response;

public record ReservationHistoryResponse(
        long totalReservationCount,
        long completedCount,
        long cancelCount,
        long noShowCount
) {
}
