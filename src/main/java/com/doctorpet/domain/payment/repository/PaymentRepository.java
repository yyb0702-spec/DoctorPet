package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 예약당 활성 결제(superseded_at IS NULL) 존재 여부. 청구 선기록·초안 게이트의 이중 청구 사전 차단은
    // "결제가 있으면"이 아니라 "활성 결제가 있으면"으로 판단한다 — 정정 재청구는 전액 환불된 결제(REFUNDED,
    // 여전히 활성)를 대체해 새 결제를 만들어야 하므로, 대체된 과거 결제는 게이트에 걸리지 않아야 한다(고도화 3.5-a).
    @Query("""
            select case when count(p) > 0 then true else false end
              from Payment p
             where p.reservationId = :reservationId
               and p.supersededAt is null
            """)
    boolean existsActiveByReservationId(@Param("reservationId") Long reservationId);

    // 예약의 현재 활성 결제. 초안 게이트가 "활성 결제가 REFUNDED일 때만 정정 초안 허용"을 판정할 때 상태를 본다.
    @Query("""
            select p
              from Payment p
             where p.reservationId = :reservationId
               and p.supersededAt is null
            """)
    Optional<Payment> findActiveByReservationId(@Param("reservationId") Long reservationId);

    Optional<Payment> findByReservationId(Long reservationId);

    // 예약의 모든 결제를 최신순으로. 정정·복구 재청구로 대체된 과거 결제도 이력으로 남으므로(고도화 3.3·3.5-a),
    // 결제 내역은 예약당 0..N건이다. 단건 조회(findByReservationId)는 다중 행에서 NonUnique로 깨지므로 이력은 이 목록으로 읽는다.
    List<Payment> findByReservationIdOrderByIdDesc(Long reservationId);

    // 예약의 활성 결제 행 락(superseded_at IS NULL). 결제 행이 존재하면 활성은 정확히 1건이다 — 대체는 항상 새 활성
    // 결제 INSERT와 원자적이라, 예약에 결제가 하나라도 있으면 그중 정확히 하나가 활성이다(고도화 3.3·3.5-a). 활성 필터가
    // 없으면 대체 이력이 쌓인 예약에서 다중 행이 잡혀 NonUnique로 깨진다. 리뷰 작성권·예약 결제수단 재지정 게이트가
    // "현재 결제가 있는지/어떤 상태인지"를 이 조회로 판단하며, 그 답은 활성 결제의 상태여야 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
              from Payment p
             where p.reservationId = :reservationId
               and p.supersededAt is null
            """)
    Optional<Payment> findByReservationIdForUpdate(
            @Param("reservationId") Long reservationId
    );

    // 결제 행 락. "결제 확인 중" 안내를 STUCK 결제에 발행할 때, 현재도 PENDING인지 확인과 안내 저장을 한 트랜잭션에서
    // 원자적으로 처리하기 위해 행을 잠근다 — 그 사이 청구 후확정·웹훅이 PAID로 확정하는 조건부 UPDATE(markPaidIfPending
    // 등, WHERE id=...)를 같은 행 락에 직렬화해, 완료 알림 뒤에 뒤늦은 안내가 저장되는 경합을 막는다(PR #139 리뷰 P1).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
              from Payment p
             where p.id = :id
            """)
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

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

    /*
      오프라인 정산(#36). OFFLINE_REQUIRED → OFFLINE_PAID 전이를 조건부 UPDATE로 원자적으로 처리한다
      (SA §5 상태 전이 보호 규칙). WHERE status = OFFLINE_REQUIRED 이므로 동시에 여러 스태프가 정산해도
      갱신 행 수가 1인 요청 하나만 실제 정산되고 나머지는 0을 받아 상위에서 멱등/거부로 처리한다.
      채널을 OFFLINE로 확정하고 처리자·시각을 감사 컬럼에 남긴다. settledAt은 JVM 기본 시간대가 아니라
      서울 기준 Clock(applicationClock) 값으로 넘겨 createdAt/updatedAt과 시간대를 맞춘다(SA §9-4, PR #80 P2).
      JPQL bulk UPDATE는 @LastModifiedDate(updatedAt)를 우회하므로, 상태를 OFFLINE_PAID로 바꾸면서 updatedAt도
      같은 settledAt으로 명시 갱신한다 — 빠뜨리면 정산 후에도 updatedAt이 옛 값으로 남는다(PR #80 P2 후속).
      clearAutomatically로 영속성 컨텍스트를 비워 이후 재조회가 갱신 결과를 읽게 한다.

      전제에 superseded_at IS NULL(활성)을 포함한다(고도화 3.3 STRICT — 이중 수납 방지). 보호자의 셀프 복구가
      먼저 이 OFFLINE_REQUIRED 결제를 대체(superseded_at 설정)했다면, 정산은 여기서 0건이 되어 대체된 행을
      OFFLINE_PAID로 만들지 못한다. 이 조건이 없으면 셀프 복구의 새 결제가 PAID가 되는 동시에 대체된 원 행이
      OFFLINE_PAID가 되어 한 예약에서 자동결제와 현장 수납이 동시에 성립한다(SA §5-2·§9-4). 두 조건부 UPDATE가
      같은 원 행을 대상으로 하므로 행 락으로 직렬화되고 먼저 커밋한 쪽만 성립한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_PAID,
                   p.paymentChannel = com.doctorpet.domain.payment.entity.PaymentChannel.OFFLINE,
                   p.offlineSettledAt = :settledAt,
                   p.offlineSettledBy = :staffMemberId,
                   p.updatedAt = :settledAt
             where p.id = :paymentId
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_REQUIRED
               and p.supersededAt is null
            """)
    int settleOfflineIfRequired(
            @Param("paymentId") Long paymentId,
            @Param("settledAt") LocalDateTime settledAt,
            @Param("staffMemberId") Long staffMemberId
    );

    /*
      정정 재청구(3.5-a)의 대체. 전액 환불된 REFUNDED 활성 결제의 superseded_at을 세워 비활성으로 내린다. 이 UPDATE가
      1건 성립한 트랜잭션에서만 새 PENDING(correction_of)을 만든다 — 동시 정정 재청구는 이 조건부 UPDATE에서 하나만
      1건을 받고 나머지는 0건이라 상위에서 409(PAYMENT_ALREADY_SUPERSEDED)로 거부된다. 상태값은 바꾸지 않고
      superseded_at만 세워 REFUNDED 이력을 보존한다. bulk UPDATE는 @LastModifiedDate를 우회하므로 updatedAt도
      같은 now로 명시 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.supersededAt = :now,
                   p.updatedAt = :now
             where p.id = :paymentId
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.REFUNDED
               and p.supersededAt is null
            """)
    int supersedeIfRefunded(
            @Param("paymentId") Long paymentId,
            @Param("now") LocalDateTime now
    );

    /*
      셀프 복구(3.3)의 대체. OFFLINE_REQUIRED 활성 결제의 superseded_at을 세워 비활성으로 내린다. 이 UPDATE가 1건
      성립한 트랜잭션에서만 새 PENDING(recovery_of)을 만든다. settleOfflineIfRequired와 같은 원 행·같은 superseded_at
      IS NULL 전제를 걸어, 셀프 복구와 오프라인 정산 중 먼저 커밋한 쪽만 성립하고 늦은 쪽은 0건이다(이중 수납 방지,
      SA §5-2·§9-4). bulk UPDATE는 @LastModifiedDate를 우회하므로 updatedAt도 같은 now로 명시 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.supersededAt = :now,
                   p.updatedAt = :now
             where p.id = :paymentId
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_REQUIRED
               and p.supersededAt is null
            """)
    int supersedeIfOfflineRequired(
            @Param("paymentId") Long paymentId,
            @Param("now") LocalDateTime now
    );

    /*
      전액 환불(#37). PAID → REFUNDED 전이를 조건부 UPDATE로 원자화한다(SA §5 상태 전이 보호 규칙).
      WHERE status = PAID이므로, 환불 선점(payment_refunds)을 통과한 요청이라도 그 사이 결제 상태가 바뀌었다면
      갱신 0건이 되어 상태를 덮어쓰지 못한다.

      payment_channel은 그대로 둔다 — 어떤 수단으로 결제된 건을 되돌렸는지가 이력으로 남아야 한다.
      JPQL bulk UPDATE는 @LastModifiedDate(updatedAt)를 우회하므로 refundedAt과 같은 서울 기준 Clock 값으로
      updatedAt을 명시 갱신한다(markPaidIfPending·settleOfflineIfRequired와 같은 함정).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = com.doctorpet.domain.payment.entity.PaymentStatus.REFUNDED,
                   p.refundedAt = :refundedAt,
                   p.updatedAt = :refundedAt
             where p.id = :paymentId
               and p.status = com.doctorpet.domain.payment.entity.PaymentStatus.PAID
            """)
    int markRefundedIfPaid(
            @Param("paymentId") Long paymentId,
            @Param("refundedAt") LocalDateTime refundedAt
    );
}
