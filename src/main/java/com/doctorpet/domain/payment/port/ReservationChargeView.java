package com.doctorpet.domain.payment.port;

/**
 * 청구에 필요한 예약 정보의 최소 뷰(크로스팀 계약 #42). 결제 도메인이 예약 상태 enum에 결합하지 않도록
 * "진료 완료 여부"를 boolean으로만 노출한다. 예약 도메인(#27)이 이 계약을 구현한다.
 *
 * @param reservationId      예약 식별자
 * @param hospitalId         예약이 속한 병원(자병원 권한 검증용)
 * @param guardianMemberId   예약 보호자 회원 — 청구에 쓸 결제수단 소유자(결제수단 소유권 조회에 사용)
 * @param paymentMethodId    예약 시 확정된 결제수단(SA §9-4 — 청구는 예약에 확정된 결제수단으로만 한다)
 * @param treatmentCompleted 진료 완료 여부. false면 청구 불가(SA §5-1 TREATMENT_COMPLETED)
 */
public record ReservationChargeView(
        Long reservationId,
        Long hospitalId,
        Long guardianMemberId,
        Long paymentMethodId,
        boolean treatmentCompleted
) {
}
