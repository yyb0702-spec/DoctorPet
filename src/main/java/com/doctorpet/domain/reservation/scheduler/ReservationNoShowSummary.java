package com.doctorpet.domain.reservation.scheduler;

public record ReservationNoShowSummary(
        boolean locked,
        int scanned,
        int processed,
        int skipped,
        int failed,
        long maxDelayMillis
) {
    public static ReservationNoShowSummary lockSkipped() {
        return new ReservationNoShowSummary(false, 0, 0, 0, 0, 0L);
    }
}
