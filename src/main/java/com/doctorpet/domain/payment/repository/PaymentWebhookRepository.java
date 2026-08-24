package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentWebhook;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/*
  결제 웹훅 수신 기록 저장소(#48). webhook_id로 멱등 판별하고(같은 이벤트 재전송 흡수), 경쟁 시
  UNIQUE(webhook_id) 위반으로 최종 방어한다. 재조회 선점·완료·해제는 조건부 UPDATE로 원자 처리한다 —
  서비스는 외부 재조회를 트랜잭션 밖에서 하므로, 각 상태 전이만 짧은 트랜잭션으로 수행한다(PR #96 리뷰 P2).
 */
public interface PaymentWebhookRepository extends JpaRepository<PaymentWebhook, Long> {

    Optional<PaymentWebhook> findByWebhookId(String webhookId);

    /**
     * 재조회를 선점한다. 미처리(processed_at IS NULL)이고 아직 선점되지 않은(reconcile_started_at IS NULL)
     * 경우에만 성공해 1을 반환한다. 동시 재수신·이미 처리된 건은 0을 반환해 재조회를 건너뛰게 한다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentWebhook w
               set w.reconcileStartedAt = :now
             where w.webhookId = :webhookId
               and w.processedAt is null
               and w.reconcileStartedAt is null
            """)
    int claimForReconcile(@Param("webhookId") String webhookId, @Param("now") LocalDateTime now);

    /**
     * 재조회 완료를 확정한다. 이후 같은 webhook_id 재전송은 무시된다. 완료와 동시에 선점 표시
     * (reconcile_started_at)도 비워, 처리 완료 건이 "진행 중"으로 남지 않게 한다(SA §4 정의 정합,
     * PR #96 리뷰 반영). 미지원 이벤트 경로는 선점을 잡지 않아 이미 null이므로 영향이 없다.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentWebhook w
               set w.processedAt = :now,
                   w.reconcileStartedAt = null
             where w.webhookId = :webhookId
               and w.processedAt is null
            """)
    int markProcessed(@Param("webhookId") String webhookId, @Param("now") LocalDateTime now);

    /** 재조회 실패로 선점을 해제해 재전송 때 재구동 가능하게 한다(미처리 건에만 적용). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentWebhook w
               set w.reconcileStartedAt = null
             where w.webhookId = :webhookId
               and w.processedAt is null
            """)
    int releaseReconcileClaim(@Param("webhookId") String webhookId);
}
