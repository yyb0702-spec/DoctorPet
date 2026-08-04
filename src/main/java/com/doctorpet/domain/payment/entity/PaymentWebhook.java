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
  멱등 레코드다. UNIQUE(payment_id, event_type)가 같은 결제·같은 이벤트의 중복 수신을 DB에서 막는다 —
  같은 payment의 서로 다른 이벤트(예: Paid/Cancelled)는 각각 1건씩 기록된다. 상태 전이 자체는 이 테이블이 아니라
  재조회 후 finalizeOutcome(조건부 UPDATE)이 멱등하게 처리한다(웹훅=트리거).
 */
@Getter
@Entity
@Table(
        name = "payment_webhooks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_webhooks_payment_event", columnNames = {"payment_id", "event_type"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 도메인 경계를 넘지 않는 결제 레코드 참조(payments.id). 앱 계층에서 무결성을 보장한다(FK 값, SA §4).
    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    // PortOne 이벤트 타입(예: Transaction.Paid). 멱등키의 일부다.
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    // 수신 시각(감사). 서울 기준 applicationClock으로 생성한다(§9-7·v1.23).
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private PaymentWebhook(Long paymentId, String eventType, LocalDateTime receivedAt) {
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }

    /** 수신 기록 생성. receivedAt은 호출부가 공통 Clock으로 만들어 넘긴다(테스트 시간 고정). */
    public static PaymentWebhook received(Long paymentId, String eventType, LocalDateTime receivedAt) {
        return new PaymentWebhook(paymentId, eventType, receivedAt);
    }
}
