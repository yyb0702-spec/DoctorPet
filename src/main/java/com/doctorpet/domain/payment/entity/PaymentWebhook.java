package com.doctorpet.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
  결제 웹훅 수신 기록(SA §4 payment_webhooks, 이슈 #48). PortOne 결제 이벤트를 중복 없이 1회만 반영하기 위한
  멱등 레코드다. 멱등키는 PortOne이 부여하는 webhook_id(Standard Webhooks 이벤트 식별자)다 — 같은 이벤트의
  재전송은 같은 webhook_id를 쓰고, 서로 다른 이벤트는 event_type이 같아도 다른 webhook_id를 갖는다. 따라서
  UNIQUE(webhook_id)가 정확한 중복 방어이며, 같은 결제에서 같은 event_type이 정상적으로 다시 발생해도 서로 다른
  이벤트로 각각 처리된다(PR #96 리뷰 반영 — 기존 (payment_id, event_type) 조합의 오탐 해소).

  processed_at은 재조회까지 끝난 시각이다. 수신 기록만 되고 처리 전 실패하면 null로 남아, 재전송 때 재구동한다
  (조정 실패한 웹훅이 재시도에서 영구 유실되던 문제 해소, PR #96 리뷰 반영). 상태 전이 자체는 이 테이블이 아니라
  재조회 후 finalizeOutcome(조건부 UPDATE)이 멱등하게 처리한다(웹훅=트리거).

  reconcile_started_at은 재조회 선점 표시다. 첫 수신이 재조회하는 동안(processed_at 확정 전) 같은 webhook_id가
  다시 들어와도, "미처리이고 미선점"일 때만 선점에 성공하는 조건부 UPDATE로 재조회를 정확히 1회로 막는다 —
  진행 중(선점됨)과 이전 실패(선점 해제됨)를 구분한다(PR #96 리뷰 P2). 재조회가 실패하면 선점을 다시 null로
  풀어 재전송 때 재구동하고, 재조회가 완료되면 markProcessed가 processed_at과 함께 선점을 비워 완료 건이
  "진행 중"으로 남지 않게 한다(PR #96 리뷰 반영). 앱 크래시로 선점이 남는 극단적 경우는 #35 정산 스케줄러가
  결제를 백업 확정한다.
 */
@Getter
@Entity
@Table(
        name = "payment_webhooks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_webhooks_webhook_id", columnNames = {"webhook_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // PortOne이 부여한 이벤트 식별자(Standard Webhooks webhook-id 헤더). 멱등키.
    @Column(name = "webhook_id", nullable = false, length = 100)
    private String webhookId;

    // 도메인 경계를 넘지 않는 결제 레코드 참조(payments.id). 앱 계층에서 무결성을 보장한다(FK 값, SA §4).
    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    // PortOne 이벤트 타입(예: Transaction.Paid). 감사·처리 분기용(멱등키는 webhook_id).
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    // 수신 시각(감사). 서울 기준 applicationClock으로 생성한다(§9-7·v1.23).
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    // 재조회까지 끝난 시각. null이면 아직 처리 전(재전송 시 재구동 대상).
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    // 재조회 선점 시각. non-null이면 다른 수신이 재조회 중 → 동시 재수신은 재조회를 건너뛴다. 실패 시 다시
    // null로 풀어 재전송 때 재구동되게 한다(PR #96 리뷰 P2 — 동시 double-reconcile 방지).
    @Column(name = "reconcile_started_at")
    private LocalDateTime reconcileStartedAt;

    private PaymentWebhook(String webhookId, Long paymentId, String eventType, LocalDateTime receivedAt) {
        this.webhookId = webhookId;
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }

    /** 수신 기록 생성(처리 전). receivedAt은 호출부가 공통 Clock으로 만들어 넘긴다(테스트 시간 고정). */
    public static PaymentWebhook received(String webhookId, Long paymentId, String eventType,
                                          LocalDateTime receivedAt) {
        return new PaymentWebhook(webhookId, paymentId, eventType, receivedAt);
    }

    /**
     * 재조회까지 끝났음을 표시한다. 이후 같은 webhook_id 재전송은 무시된다. 완료와 동시에 선점 표시
     * (reconcileStartedAt)도 비워 처리 완료 건이 "진행 중"으로 남지 않게 한다(PR #96 리뷰 반영).
     */
    public void markProcessed(LocalDateTime processedAt) {
        this.processedAt = processedAt;
        this.reconcileStartedAt = null;
    }

    /** 아직 재조회가 끝나지 않았는지(재구동 대상). */
    public boolean isProcessed() {
        return processedAt != null;
    }
}
