package com.doctorpet.domain.reservation.adapter;

import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
  결제 도메인의 ReservationLookupPort(청구용 예약 조회) 구현. 헥사고날 — 소비자(payment)가 선언한 계약을
  제공자(reservation)가 구현한다. reservation은 자기 Repository만 사용하므로 "다른 도메인 Repository 직접
  호출 금지" 가드레일에 걸리지 않고, payment는 reservation 내부를 모른 채 port로만 의존한다.

  진료 완료 여부는 예약 상태 enum에 payment가 결합하지 않도록 boolean(status == TREATMENT_COMPLETED)으로만
  노출한다(#42 크로스팀 계약). 이 빈이 등록되면 결제 도메인의 임시 placeholder는 필요 없어져 제거됐다.
 */
@Component
@RequiredArgsConstructor
public class ReservationChargeLookupAdapter implements ReservationLookupPort {

    private final ReservationRepository reservationRepository;

    @Override
    public Optional<ReservationChargeView> findForCharge(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .map(reservation -> new ReservationChargeView(
                        reservation.getId(),
                        reservation.getHospitalId(),
                        reservation.getMemberId(),
                        reservation.getPaymentMethodId(),
                        reservation.getStatus() == ReservationStatus.TREATMENT_COMPLETED));
    }
}
