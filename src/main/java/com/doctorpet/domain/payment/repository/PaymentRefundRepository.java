package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentRefund;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
  환불 이력 조회·선점(#37). 전액 환불만 지원하므로 결제당 최대 1행이고(UNIQUE(payment_id)), 그 행의
  merchant_refund_id는 재시도에서도 재사용된다.

  선점(claim) 규칙 — 동시 환불 요청이 PG 취소를 두 번 호출하지 않게 하는 핵심이다:
  - 행이 없으면 INSERT 자체가 선점이다(UNIQUE(payment_id)가 승자 1건만 통과시킨다).
  - 행이 있으면 아래 두 조건부 UPDATE 중 하나로만 선점을 넘겨받는다. 둘 다 갱신 0건이면 진 요청이므로
    상위에서 멱등 응답 또는 409로 처리한다. JPQL bulk UPDATE는 @LastModifiedDate를 우회하므로
    updatedAt을 서울 기준 Clock 값으로 명시 갱신한다(payments 조건부 UPDATE와 같은 함정).
 */
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    Optional<PaymentRefund> findByPaymentId(Long paymentId);

    Optional<PaymentRefund> findByMerchantRefundId(String merchantRefundId);

    /*
      실패한 환불의 재시도 선점. WHERE status='FAILED'라 동시 재시도 중 1건만 성립한다.
      사유·처리자를 이번 요청 값으로 갱신한다 — 실제로 환불을 완료시킨 스태프가 감사 기록에 남아야 하고,
      앞선 실패 시도의 처리자는 로그에 남는다. failure_reason은 비워 재시도 전 상태로 되돌린다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentRefund r
               set r.status = com.doctorpet.domain.payment.entity.RefundStatus.REQUESTED,
                   r.reason = :reason,
                   r.refundedBy = :refundedBy,
                   r.failureReason = null,
                   r.claimedAt = :now,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = com.doctorpet.domain.payment.entity.RefundStatus.FAILED
            """)
    int claimFailedForRetry(
            @Param("id") Long id,
            @Param("reason") String reason,
            @Param("refundedBy") Long refundedBy,
            @Param("now") LocalDateTime now
    );

    /*
      멈춘 REQUESTED 선점 회수. claimed_at이 임계보다 오래된 건만 대상이라, 지금 PG 취소가 진행 중인 요청을
      가로채지 않는다. 같은 merchant_refund_id로 재호출하므로 PG는 기존 취소 결과를 돌려주고(이중 취소 없음),
      "PG는 취소됐는데 payments만 PAID로 멈춘" 상태가 이 경로로 복구된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentRefund r
               set r.reason = :reason,
                   r.refundedBy = :refundedBy,
                   r.claimedAt = :now,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = com.doctorpet.domain.payment.entity.RefundStatus.REQUESTED
               and r.claimedAt < :staleThreshold
            """)
    int claimStaleRequested(
            @Param("id") Long id,
            @Param("reason") String reason,
            @Param("refundedBy") Long refundedBy,
            @Param("staleThreshold") LocalDateTime staleThreshold,
            @Param("now") LocalDateTime now
    );

    /*
      PG 취소 성공 확정. WHERE status='REQUESTED'라 선점을 잃은 요청은 갱신 0건이 되어 결과를 덮어쓰지 못한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentRefund r
               set r.status = com.doctorpet.domain.payment.entity.RefundStatus.COMPLETED,
                   r.pgCancelId = :pgCancelId,
                   r.refundedAt = :refundedAt,
                   r.failureReason = null,
                   r.updatedAt = :refundedAt
             where r.id = :id
               and r.status = com.doctorpet.domain.payment.entity.RefundStatus.REQUESTED
            """)
    int markCompletedIfRequested(
            @Param("id") Long id,
            @Param("pgCancelId") String pgCancelId,
            @Param("refundedAt") LocalDateTime refundedAt
    );

    /*
      PG 취소 실패 기록. payments는 PAID로 남으므로 같은 멱등키로 재시도할 수 있다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentRefund r
               set r.status = com.doctorpet.domain.payment.entity.RefundStatus.FAILED,
                   r.failureReason = :failureReason,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = com.doctorpet.domain.payment.entity.RefundStatus.REQUESTED
            """)
    int markFailedIfRequested(
            @Param("id") Long id,
            @Param("failureReason") String failureReason,
            @Param("now") LocalDateTime now
    );
}
