package com.doctorpet.domain.payment.port;

/**
 * 영수증에 필요한 예약 정보의 최소 뷰(고도화 결제 3.4). 청구용 {@link ReservationChargeView}와 분리한 이유는
 * 필요한 정보가 다르기 때문이다 — 청구는 "청구해도 되는가"(진료 완료·결제수단)를 묻고, 영수증은 "누구의 무엇에 대한
 * 증빙인가"(병원·보호자·펫)를 묻는다. 기존 계약에 필드를 덧붙이면 청구 경로가 쓰지도 않는 값을 매번 싣게 된다.
 *
 * <p>펫 이름·종은 예약 시점 스냅샷(SA §4 reservations)이라 프로필이 삭제돼도 영수증이 깨지지 않는다.
 * 병원명·보호자명 같은 표시용 정보는 담지 않는다 — 이 PR의 범위는 식별 가능한 기존 도메인 정보까지다.
 *
 * @param reservationId    예약 식별자
 * @param hospitalId       예약이 속한 병원(스태프 자병원 검증·영수증 표기)
 * @param guardianMemberId 예약 보호자 회원(본인 조회 검증·영수증 표기)
 * @param petId            대상 반려동물 식별자
 * @param petName          예약 시점 반려동물 이름 스냅샷
 * @param petSpecies       예약 시점 반려동물 종 스냅샷
 */
public record ReservationReceiptView(
        Long reservationId,
        Long hospitalId,
        Long guardianMemberId,
        Long petId,
        String petName,
        String petSpecies
) {
}
