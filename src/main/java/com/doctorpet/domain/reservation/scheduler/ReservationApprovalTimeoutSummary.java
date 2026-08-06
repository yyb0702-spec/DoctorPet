package com.doctorpet.domain.reservation.scheduler;

/** 예약 승인 타임아웃 배치 1회의 처리 결과. */
public record ReservationApprovalTimeoutSummary(
        boolean locked,
        int scanned,
        int processed,
        int skipped,
        int failed,
        long maxDelayMillis
) {

    public static ReservationApprovalTimeoutSummary lockSkipped() {
        return new ReservationApprovalTimeoutSummary(
                false, 0, 0, 0, 0, 0L
        );
    }
}
