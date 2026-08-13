package com.doctorpet.domain.payment.port;

import java.util.Optional;

/**
 * 예약↔결제 계약(port). 결제 도메인은 예약 Repository를 직접 호출하지 않고 이 계약으로만 예약을 조회한다
 * (가드레일 — 다른 도메인 Repository 직접 호출 금지, 조합은 ApplicationService).
 *
 * <p>예약 도메인(#27)이 develop에 병합되면 이 인터페이스를 구현한 어댑터(@Component)를 제공한다.
 * 그 전까지 #34는 이 계약에만 의존하고 테스트에서 목으로 검증한다.
 */
public interface ReservationLookupPort {

    /**
     * 청구 대상 예약의 최소 뷰를 반환한다. 존재하지 않으면 {@link Optional#empty()}.
     * (진료 완료·자병원 여부의 판단은 반환값을 받은 상위 서비스가 수행한다.)
     */
    Optional<ReservationChargeView> findForCharge(Long reservationId);

    /** 청구 선기록 동안 결제수단 재지정과 직렬화할 예약 행 락 조회다. */
    Optional<ReservationChargeView> findForChargeForUpdate(Long reservationId);

    /**
     * 영수증 발급 대상 예약의 최소 뷰를 반환한다(고도화 결제 3.4). 존재하지 않으면 {@link Optional#empty()}.
     * 소유권(보호자 본인·스태프 자병원) 판단은 반환값을 받은 상위 서비스가 수행한다.
     */
    Optional<ReservationReceiptView> findForReceipt(Long reservationId);
}
