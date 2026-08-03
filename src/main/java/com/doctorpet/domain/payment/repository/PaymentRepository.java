package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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
      정산 스케줄러(#35, SA §9-7)의 대상 조회. 일정 시간(threshold) 이상 status로 머문 결제를 오래된 순으로
      배치 크기만큼 가져온다. updatedAt 기준이라 재조회로 갱신된 건은 다음 주기까지 자연히 밀린다(백오프).
      대상 인덱스는 (status, updated_at)이다.

      단, PG가 PAID를 줬으나 금액·식별자가 불일치해 이미 돈이 이동했을 수 있는 "불확실 결제"는 조회에서 제외한다 —
      재조회 결과가 FAILED로 오면 OFFLINE_REQUIRED(현장 재수납)로 전환돼 이중결제가 될 수 있어, 자동 확정 대상이
      아니라 운영자 수동 확인 대상으로 남긴다(SA §9-7 — 불확실 결제 자동 확정 금지). 제외 사유는 두 경로에서 온다:
      - 청구 단계(PaymentApplicationService.confirmPaid): AMOUNT_MISMATCH·INVALID_PG_RESULT
      - 정산 단계(resolveByQuery): RECONCILE_AMOUNT_MISMATCH (금액 불일치·pgId 누락 모두 이 사유로 PENDING 유지)
     */
    @Query("""
            select p from Payment p
             where p.status = :status
               and p.updatedAt < :threshold
               and (p.failureReason is null
                    or p.failureReason not in ('AMOUNT_MISMATCH', 'INVALID_PG_RESULT', 'RECONCILE_AMOUNT_MISMATCH'))
             order by p.updatedAt asc
            """)
    List<Payment> findReconcileTargets(
            @Param("status") PaymentStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable
    );

    /*
      후확정(#34) ↔ 정산(#35)의 동시 경합을 막는 조건부 상태 전이(PR #81 P1 리뷰). Payment에는 @Version이 없어
      findById 후 메모리 검사(ensurePending)만으로는 두 트랜잭션이 같은 PENDING을 읽고 둘 다 확정해 마지막 커밋이
      앞선 결과를 덮어쓸 수 있다. WHERE status='PENDING' 조건부 UPDATE로 전이를 원자화하고, 갱신 0건이면 이미 확정된
      것이므로 호출부가 상태를 덮어쓰거나 알림을 중복 발행하지 않도록 한다.

      JPQL bulk UPDATE는 Hibernate 생명주기를 우회해 @LastModifiedDate(updatedAt)를 자동 갱신하지 않으므로
      updatedAt을 서울 기준 Clock 값으로 명시 갱신한다 — remainPending(상태 유지)에서 이를 빠뜨리면 정산 백오프
      (updatedAt 기준으로 다음 주기까지 미루기)가 깨진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = com.doctorpet.domain.payment.entity.PaymentStatus.PAID,
                   p.paymentChannel = com.doctorpet.domain.payment.entity.PaymentChannel.BILLING_KEY,
                   p.pgPaymentId = :pgPaymentId,
                   p.paidAt = :paidAt,
                   p.updatedAt = :now
             where p.id = :id
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.PENDING
            """)
    int markPaidIfPending(
            @Param("id") Long id,
            @Param("pgPaymentId") String pgPaymentId,
            @Param("paidAt") LocalDateTime paidAt,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_REQUIRED,
                   p.failureReason = :failureReason,
                   p.retryCount = :retryCount,
                   p.offlineRequiredAt = :now,
                   p.updatedAt = :now
             where p.id = :id
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.PENDING
            """)
    int markOfflineRequiredIfPending(
            @Param("id") Long id,
            @Param("failureReason") String failureReason,
            @Param("retryCount") int retryCount,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.retryCount = :retryCount,
                   p.failureReason = :failureReason,
                   p.updatedAt = :now
             where p.id = :id
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.PENDING
            """)
    int remainPendingIfPending(
            @Param("id") Long id,
            @Param("retryCount") int retryCount,
            @Param("failureReason") String failureReason,
            @Param("now") LocalDateTime now
    );
}
