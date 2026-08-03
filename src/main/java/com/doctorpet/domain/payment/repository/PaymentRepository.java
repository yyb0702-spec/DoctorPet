package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
  진료비 결제 조회(#34). 예약당 1건이라 reservation_id로 존재 여부·단건을 확인한다.
  이중 청구 차단은 DB UNIQUE(reservation_id·merchant_payment_id)가 최종 방어선이고,
  existsByReservationId는 정상 경로에서 먼저 걸러 명확한 도메인 에러(DUPLICATE_CHARGE)를 주기 위한 사전 체크다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByReservationId(Long reservationId);

    Optional<Payment> findByReservationId(Long reservationId);

    Optional<Payment> findByMerchantPaymentId(String merchantPaymentId);
}
