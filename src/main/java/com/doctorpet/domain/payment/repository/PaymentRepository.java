package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
      정산 스케줄러(#35, SA §9-7)의 대상 조회. 일정 시간(threshold) 이상 status로 머문 결제를 오래된 순으로
      배치 크기만큼 가져온다. updatedAt 기준이라 재조회로 갱신된 건은 다음 주기까지 자연히 밀린다(백오프).
      대상 인덱스는 (status, updated_at)이다.

      단, 청구 단계에서 PG가 PAID를 줬으나 금액·식별자가 불일치해(PaymentApplicationService.confirmPaid의
      AMOUNT_MISMATCH·INVALID_PG_RESULT) PENDING으로 남은 건은 조회에서 제외한다 — 이미 승인돼 돈이 이동했을 수
      있어, 재조회 결과가 FAILED로 오면 OFFLINE_REQUIRED(현장 재수납)로 전환돼 이중결제가 될 수 있다. 이 건들은
      자동 확정 대상이 아니라 운영자 수동 확인 대상으로 남긴다(SA §9-7 — 불확실 결제 자동 확정 금지).
     */
    @Query("""
            select p from Payment p
             where p.status = :status
               and p.updatedAt < :threshold
               and (p.failureReason is null
                    or p.failureReason not in ('AMOUNT_MISMATCH', 'INVALID_PG_RESULT'))
             order by p.updatedAt asc
            """)
    List<Payment> findReconcileTargets(
            @Param("status") PaymentStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable
    );
}
