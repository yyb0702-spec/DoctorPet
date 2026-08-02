package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.Payment;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
  진료비 결제 조회(#34). 예약당 1건이라 reservation_id로 존재 여부·단건을 확인한다.
  이중 청구 차단은 DB UNIQUE(reservation_id·merchant_payment_id)가 최종 방어선이고,
  existsByReservationId는 정상 경로에서 먼저 걸러 명확한 도메인 에러(DUPLICATE_CHARGE)를 주기 위한 사전 체크다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    boolean existsByReservationId(Long reservationId);

    Optional<Payment> findByReservationId(Long reservationId);

    Optional<Payment> findByMerchantPaymentId(String merchantPaymentId);

    /*
      오프라인 정산(#36). OFFLINE_REQUIRED → OFFLINE_PAID 전이를 조건부 UPDATE로 원자적으로 처리한다
      (SA §5 상태 전이 보호 규칙). WHERE status = OFFLINE_REQUIRED 이므로 동시에 여러 스태프가 정산해도
      갱신 행 수가 1인 요청 하나만 실제 정산되고 나머지는 0을 받아 상위에서 멱등/거부로 처리한다.
      채널을 OFFLINE로 확정하고 처리자·시각을 감사 컬럼에 남긴다. clearAutomatically로 영속성 컨텍스트를
      비워 이후 재조회가 갱신 결과를 읽게 한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_PAID,
                   p.paymentChannel = com.doctorpet.domain.payment.entity.PaymentChannel.OFFLINE,
                   p.offlineSettledAt = :settledAt,
                   p.offlineSettledBy = :staffMemberId
             where p.id = :paymentId
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_REQUIRED
            """)
    int settleOfflineIfRequired(
            @Param("paymentId") Long paymentId,
            @Param("settledAt") LocalDateTime settledAt,
            @Param("staffMemberId") Long staffMemberId
    );
}
