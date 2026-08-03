package com.doctorpet.domain.payment.scheduler;

/**
 * 결제 정산 배치 1회 실행 요약(#35). 처리량·결과 분포를 관측(로그)해 정산 파이프라인의 상태를 수치로 남긴다.
 *
 * @param locked        락을 얻어 실제로 실행했는지(false면 다른 인스턴스가 실행 중이라 스킵)
 * @param scanned       조회된 대상 건수
 * @param paid          PAID로 확정한 건수
 * @param offlineRequired OFFLINE_REQUIRED로 확정한 건수(재시도 소진·실패)
 * @param stillPending  이번에도 미확정이라 PENDING 유지한 건수
 * @param errored       처리 중 예외로 건너뛴 건수
 */
public record ReconcileSummary(
        boolean locked,
        int scanned,
        int paid,
        int offlineRequired,
        int stillPending,
        int errored
) {

    public static ReconcileSummary skipped() {
        return new ReconcileSummary(false, 0, 0, 0, 0, 0);
    }
}
