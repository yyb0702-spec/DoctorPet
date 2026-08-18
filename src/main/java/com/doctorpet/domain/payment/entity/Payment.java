package com.doctorpet.domain.payment.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
  진료비 결제(SA §4 payments, §5-2, §9-4, 이슈 #34). 예약당 **활성 결제 1건**(고도화 3.3·3.5-a).
  - reservation_id·payment_method_id는 도메인 경계를 넘는 참조라 @ManyToOne이 아니라 FK 값(Long)으로 둔다(가드레일·SA §4).
  - 이중 청구 차단: 예약당 활성 결제 1건 + UNIQUE(merchant_payment_id). PENDING 선기록 후 외부 승인(트랜잭션 밖).
    활성은 상태가 아니라 superseded_at IS NULL로 판정한다. "활성 결제 1건"은 @Table의 단순 UNIQUE(reservation_id)로는
    표현할 수 없다 — 대체된 과거 결제(superseded_at 설정)도 같은 reservation_id를 이력으로 보존하기 때문이다. 그래서
    "활성일 때만 reservation_id를 내보내는" 생성 컬럼 active_reservation_id + UNIQUE로 DB가 못박고(부분 UNIQUE 부재
    보완, payment_methods.active_default_member_id와 동일 패턴), 그 DDL은 PaymentActiveConstraintMigrationRunner가
    붙인다. 여기서 uk_payments_reservation_id를 선언하면 ddl-auto=update가 재생성해 정정·복구 재청구를 막으므로
    선언하지 않는다.
  - 카드 스냅샷(brand·last4)은 청구 시점 결제수단 값을 복사해, 이후 결제수단이 삭제·만료돼도 이력이 유지되게 한다.
  - 상태 전이는 도메인 메서드로만 한다(SA §5 상태 머신 밖 전이 금지).
  - correction_of/recovery_of는 재시도 체인이다. 정정 재청구(3.5-a)는 전액 환불된 REFUNDED를 대체한 새 결제가
    correction_of로 원 결제를 가리키고, 셀프 복구(3.3)는 OFFLINE_REQUIRED를 대체한 새 결제가 recovery_of로 가리킨다.
    한 결제가 둘 다 가질 수는 없으며 CHECK로 막는다(러너가 붙인다).
 */
@Getter
@Entity
@Table(
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payments_merchant_payment_id", columnNames = "merchant_payment_id")
        },
        // 정산 스케줄러(#35)가 오래 PENDING인 결제를 조회하는 대상 인덱스(SA §9-7).
        indexes = @Index(name = "idx_payments_status_updated_at", columnList = "status, updated_at")
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

    // 전액 환불이 확정된 시각(#37). 상세 이력(사유·처리자·PG 취소 식별자)은 payment_refunds에 있고,
    // 이 컬럼은 결제 조회 응답·목록에서 매번 이력 테이블을 조인하지 않으려는 요약값이다(offline_settled_at과 같은 성격).
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    // 대체된 시각(고도화 3.3·3.5-a). NULL이면 활성 결제다 — 활성 판정은 상태가 아니라 이 컬럼으로 한다.
    // 셀프 복구(OFFLINE_REQUIRED)·정정 재청구(REFUNDED)의 대체 대상에만 세워지고, PAID·OFFLINE_PAID는 대체하지 않는다
    // (이미 수납된 결제를 대체하면 받은 돈의 근거가 사라진다, SA §5-2). 실제 대체는 조건부 UPDATE가 원자적으로 한다.
    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    // 정정 재청구 체인(3.5-a). 전액 환불된 원 결제(REFUNDED)를 대체한 새 결제가 그 원 payment_id를 가리킨다.
    @Column(name = "correction_of")
    private Long correctionOf;

    // 셀프 복구 체인(3.3). 오프라인 수납 대상(OFFLINE_REQUIRED)이던 원 결제를 대체한 새 결제가 그 원 payment_id를 가리킨다.
    @Column(name = "recovery_of")
    private Long recoveryOf;

    private Payment(
            Long reservationId, String merchantPaymentId, Long paymentMethodId,
            String cardBrandSnapshot, String cardLast4Snapshot, int amount,
            Long correctionOf, Long recoveryOf
    ) {
        this.reservationId = reservationId;
        this.merchantPaymentId = merchantPaymentId;
        this.paymentMethodId = paymentMethodId;
        this.cardBrandSnapshot = cardBrandSnapshot;
        this.cardLast4Snapshot = cardLast4Snapshot;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.retryCount = 0;
        this.correctionOf = correctionOf;
        this.recoveryOf = recoveryOf;
    }

    /**
     * 청구 준비(멱등키 선기록). status=PENDING으로 생성한다. 예약당 활성 결제 UNIQUE가 이중 청구를 막는다(SA §9-4).
     * 금액·상태·권한 검증은 Service에서 선행한다. 최초 청구라 체인 참조는 없다.
     */
    public static Payment pending(
            Long reservationId, String merchantPaymentId, Long paymentMethodId,
            String cardBrandSnapshot, String cardLast4Snapshot, int amount
    ) {
        return new Payment(reservationId, merchantPaymentId, paymentMethodId,
                cardBrandSnapshot, cardLast4Snapshot, amount, null, null);
    }

    /**
     * 정정 재청구 선기록(3.5-a). 전액 환불된 원 결제를 대체한 새 활성 결제로, correction_of로 원 결제를 가리킨다.
     * 대체 자체(원 REFUNDED의 superseded_at 설정)는 조건부 UPDATE가 같은 트랜잭션에서 원자적으로 한다.
     */
    public static Payment pendingCorrection(
            Long reservationId, String merchantPaymentId, Long paymentMethodId,
            String cardBrandSnapshot, String cardLast4Snapshot, int amount, Long correctionOf
    ) {
        return new Payment(reservationId, merchantPaymentId, paymentMethodId,
                cardBrandSnapshot, cardLast4Snapshot, amount, correctionOf, null);
    }

    /**
     * 셀프 복구 선기록(3.3). OFFLINE_REQUIRED이던 원 결제를 대체한 새 활성 결제로, recovery_of로 원 결제를 가리킨다.
     * 원 항목·총액을 승계하며, 대체 자체(원 OFFLINE_REQUIRED의 superseded_at 설정)는 조건부 UPDATE가 원자적으로 한다.
     */
    public static Payment pendingRecovery(
            Long reservationId, String merchantPaymentId, Long paymentMethodId,
            String cardBrandSnapshot, String cardLast4Snapshot, int amount, Long recoveryOf
    ) {
        return new Payment(reservationId, merchantPaymentId, paymentMethodId,
                cardBrandSnapshot, cardLast4Snapshot, amount, null, recoveryOf);
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

    /**
     * 전액 환불 확정(#37). PAID에서만 전이한다 — 오프라인 수납(OFFLINE_PAID) 환불은 범위 밖이고,
     * PENDING은 승인 여부가 불확실해 취소 대상이 아니다. 실제 전이는 조건부 UPDATE(WHERE status='PAID')가
     * 원자적으로 하고, 이 메서드는 테스트 픽스처·단위 검증용이다.
     * 채널은 결제 시점 값(BILLING_KEY)을 그대로 유지한다 — 어떤 수단으로 결제된 건을 되돌렸는지가 남아야 한다.
     */
    public void markRefunded(LocalDateTime refundedAt) {
        if (this.status != PaymentStatus.PAID) {
            throw new IllegalStateException("PAID 상태에서만 환불할 수 있습니다. 현재 상태=" + this.status);
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = refundedAt;
    }

    /**
     * 재청구로 대체돼 비활성으로 내린다(고도화 3.3·3.5-a). REFUNDED(정정 재청구)·OFFLINE_REQUIRED(셀프 복구)에서만
     * 허용한다 — 이미 수납된 PAID·OFFLINE_PAID를 대체하면 받은 돈의 근거가 사라진다(SA §5-2). 상태값은 바꾸지 않고
     * superseded_at만 세워 이력을 그대로 보존한다. 실제 대체는 조건부 UPDATE(WHERE superseded_at IS NULL)가
     * 원자적으로 하고, 이 메서드는 테스트 픽스처·단위 검증용이다.
     */
    public void supersede(LocalDateTime supersededAt) {
        if (this.status != PaymentStatus.REFUNDED && this.status != PaymentStatus.OFFLINE_REQUIRED) {
            throw new IllegalStateException(
                    "REFUNDED·OFFLINE_REQUIRED 상태에서만 대체할 수 있습니다. 현재 상태=" + this.status);
        }
        if (this.supersededAt != null) {
            throw new IllegalStateException("이미 대체된 결제입니다.");
        }
        this.supersededAt = supersededAt;
    }

    /** 활성 결제 여부(superseded_at IS NULL). 활성은 상태가 아니라 이 판정으로 한다(SA §5-2, 고도화 3.5-a). */
    public boolean isActive() {
        return this.supersededAt == null;
    }

    private void ensurePending() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 결과를 확정할 수 있습니다. 현재 상태=" + this.status);
        }
    }
}
