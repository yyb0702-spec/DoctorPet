package com.doctorpet.domain.payment.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
  진료비 결제(SA §4 payments, §5-2, §9-4, 이슈 #34). 예약당 1건.
  - reservation_id·payment_method_id는 도메인 경계를 넘는 참조라 @ManyToOne이 아니라 FK 값(Long)으로 둔다(가드레일·SA §4).
  - 이중 청구 차단: UNIQUE(reservation_id) + UNIQUE(merchant_payment_id). PENDING 선기록 후 외부 승인(트랜잭션 밖).
  - 카드 스냅샷(brand·last4)은 청구 시점 결제수단 값을 복사해, 이후 결제수단이 삭제·만료돼도 이력이 유지되게 한다.
  - 상태 전이는 도메인 메서드로만 한다(SA §5 상태 머신 밖 전이 금지).
 */
@Getter
@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payments_reservation_id", columnNames = "reservation_id"),
                @UniqueConstraint(name = "uk_payments_merchant_payment_id", columnNames = "merchant_payment_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    // 외부 요청 전 서버가 생성하는 멱등키. PortOne 승인·조회·재시도에 동일 사용(SA §9-4).
    @Column(name = "merchant_payment_id", nullable = false, length = 80)
    private String merchantPaymentId;

    @Column(name = "payment_method_id", nullable = false)
    private Long paymentMethodId;

    @Column(name = "card_brand_snapshot", length = 40)
    private String cardBrandSnapshot;

    @Column(name = "card_last4_snapshot", length = 4)
    private String cardLast4Snapshot;

    // 최종 진료비(원). 0 초과 & 절대 상한 이하 검증은 Service에서 선행한다(SA §9-4).
    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // 정산 방식 확정 시점에만 채워진다(PAID→BILLING_KEY, OFFLINE_PAID→OFFLINE). 그 전에는 null.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel", length = 20)
    private PaymentChannel paymentChannel;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    // 실패 사유 분류값(민감정보·원본 오류 원문 금지). 예: NON_RETRIABLE, RETRY_EXHAUSTED, PAYMENT_METHOD_INACTIVE.
    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    // PortOne 결제 식별자(단건조회용). 승인 성공 시 채워진다.
    @Column(name = "pg_payment_id", length = 100)
    private String pgPaymentId;

    // 자동 청구 실패로 오프라인 수납 대상(OFFLINE_REQUIRED)으로 전환된 시각(감사). markOfflineRequired 시 기록한다.
    @Column(name = "offline_required_at")
    private LocalDateTime offlineRequiredAt;

    // 오프라인 수납 감사 기록(#36에서 채워진다).
    @Column(name = "offline_settled_at")
    private LocalDateTime offlineSettledAt;

    @Column(name = "offline_settled_by")
    private Long offlineSettledBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    private Payment(
            Long reservationId, String merchantPaymentId, Long paymentMethodId,
            String cardBrandSnapshot, String cardLast4Snapshot, int amount
    ) {
        this.reservationId = reservationId;
        this.merchantPaymentId = merchantPaymentId;
        this.paymentMethodId = paymentMethodId;
        this.cardBrandSnapshot = cardBrandSnapshot;
        this.cardLast4Snapshot = cardLast4Snapshot;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.retryCount = 0;
    }

    /**
     * 청구 준비(멱등키 선기록). status=PENDING으로 생성한다. UNIQUE(reservation_id)가 이중 청구를 막는다(SA §9-4).
     * 금액·상태·권한 검증은 Service에서 선행한다.
     */
    public static Payment pending(
            Long reservationId, String merchantPaymentId, Long paymentMethodId,
            String cardBrandSnapshot, String cardLast4Snapshot, int amount
    ) {
        return new Payment(reservationId, merchantPaymentId, paymentMethodId,
                cardBrandSnapshot, cardLast4Snapshot, amount);
    }

    /** 빌링키 승인 성공. PENDING에서만 전이한다(SA §5-2). */
    public void markPaid(String pgPaymentId, LocalDateTime paidAt) {
        ensurePending();
        this.status = PaymentStatus.PAID;
        this.paymentChannel = PaymentChannel.BILLING_KEY;
        this.pgPaymentId = pgPaymentId;
        this.paidAt = paidAt;
    }

    /**
     * 재시도 무의미/재시도 소진/결제수단 비활성으로 자동 청구를 포기하고 오프라인 수납 대상으로 확정한다(SA §9-4).
     * PENDING에서만 전이한다. 채널은 오프라인 수납(#36) 시점에 OFFLINE로 확정되므로 여기선 비운다.
     */
    public void markOfflineRequired(String failureReason, int retryCount) {
        ensurePending();
        this.status = PaymentStatus.OFFLINE_REQUIRED;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        // 전환 시각은 외부 소스가 없는 시스템 감사값이라 현재 시각으로 기록한다(paidAt은 PG 승인 시각과 달리).
        this.offlineRequiredAt = LocalDateTime.now();
    }

    /**
     * 결과 미확정(타임아웃)으로 PENDING을 유지한다. 무조건 재시도하지 않고 정산 스케줄러(#35)가 단건조회로 확정한다(SA §9-4).
     * 재시도 횟수·마지막 사유만 갱신한다(상태는 PENDING 유지).
     */
    public void remainPending(int retryCount, String failureReason) {
        ensurePending();
        this.retryCount = retryCount;
        this.failureReason = failureReason;
    }

    private void ensurePending() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 결과를 확정할 수 있습니다. 현재 상태=" + this.status);
        }
    }
}
