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
  환불 이력(SA §5-2가 예고한 결제 이력 테이블, 이슈 #37). payments가 전진 단선이라 단일 행으로 이력이 보존되는데,
  환불은 PAID를 덮어쓰므로 "누가·언제·얼마를·왜" 되돌렸는지가 payments만으로는 남지 않는다. 그 이력을 여기 둔다.
  - payment_id는 도메인 내 참조지만 Payment와 같은 규칙으로 FK 값(Long)으로 둔다(가드레일·SA §4).
  - UNIQUE(payment_id)가 곧 환불 선점(claim)이다: 동시 환불 요청 중 INSERT에 성공한 1건만 PG 취소로 진행하고,
    나머지는 제약 위반으로 걸러진 뒤 기존 행 상태에 따라 멱등 응답 또는 재시도 선점으로 갈린다. 전액 환불만
    지원하는 지금은 결제당 1행이 맞고, 부분 환불 확장 시 이 제약을 떼면 1:N이 된다(SA §5-2).
  - UNIQUE(merchant_refund_id)는 PG 멱등키의 유일성이다. 재시도는 새 키를 만들지 않고 이 값을 재사용해
    PortOne이 이중 취소를 흡수하게 한다(#37 — 키가 달라지면 PG도 중복 취소를 막을 수 없다).
  - 카드번호·빌링키는 담지 않는다(보안). 표시용 카드 스냅샷은 payments에 이미 있다.
 */
@Getter
@Entity
@Table(
        name = "payment_refunds",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_refunds_payment_id", columnNames = "payment_id"),
                @UniqueConstraint(name = "uk_payment_refunds_merchant_refund_id", columnNames = "merchant_refund_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    // PG 취소 요청 전 서버가 생성하는 멱등키. 재시도에도 같은 값을 그대로 재사용한다(#37).
    @Column(name = "merchant_refund_id", nullable = false, length = 80)
    private String merchantRefundId;

    // 환불 금액(원). 전액 환불만 지원하므로 항상 payments.amount와 같다 — 부분 환불 확장 대비로 컬럼은 분리해 둔다.
    @Column(nullable = false)
    private int amount;

    // 스태프가 입력한 환불 사유. 보호자에게 노출하지 않고 감사용으로만 보관한다.
    @Column(nullable = false, length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    // PortOne 취소 식별자. 취소 성공 시 채워진다.
    @Column(name = "pg_cancel_id", length = 100)
    private String pgCancelId;

    // 실패 사유 분류값(민감정보·PG 오류 원문 금지). 예: NON_RETRIABLE, REFUND_UNCONFIRMED.
    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    // 환불을 실행한 병원 스태프 member_id(감사). 재시도로 선점을 넘겨받으면 실제로 처리한 스태프로 갱신된다.
    @Column(name = "refunded_by", nullable = false)
    private Long refundedBy;

    /*
      REQUESTED 선점이 시작된 시각. "지금 PG 취소가 진행 중"과 "앱이 죽어 멈춘 채 남은 요청"을 구분하는 유일한
      근거다(#37). 신선한 REQUESTED에 들어온 환불 요청은 409로 막고, 임계를 넘긴 REQUESTED는 조건부 UPDATE로
      선점을 넘겨받아 같은 멱등키로 재시도한다 — 같은 키 재호출은 PortOne이 기존 취소 결과를 돌려주므로
      이중 취소가 되지 않고, 오히려 이 경로가 "PG는 취소됐는데 우리 상태만 PAID로 멈춘" 행을 스스로 복구한다.
     */
    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    /*
      선점 소유권 펜스 토큰(PR #112 리뷰 P1). 선점할 때마다 새로 발급하고, 확정(COMPLETED)·실패(FAILED) 전이의
      조건에 함께 넣어 "지금 선점을 쥔 요청"만 결과를 쓸 수 있게 한다.

      없으면 이런 갈라짐이 생긴다: 임계를 넘겨 선점이 회수된 뒤에도 이전 요청의 늦은 fail()이 id·status만
      검사해 통과하므로, 새 소유자의 REQUESTED 행을 FAILED로 바꿔버린다. 그 뒤 새 소유자의 complete()는
      이력 갱신은 0건이면서 결제만 REFUNDED로 바꿔 "이력 FAILED + 결제 REFUNDED"가 남는다.

      claimed_at 비교로 대신하지 않는다 — DATETIME 정밀도에 따라 저장 시 절삭될 수 있어 동등 비교가 어긋날
      위험이 있다. 토큰은 UUID 문자열이라 정밀도와 무관하다.
     */
    @Column(name = "claim_token", nullable = false, length = 40)
    private String claimToken;

    // PG 취소가 확정된 시각. COMPLETED에서만 채워진다.
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    private PaymentRefund(
            Long paymentId, String merchantRefundId, int amount, String reason,
            Long refundedBy, LocalDateTime claimedAt, String claimToken) {
        this.paymentId = paymentId;
        this.merchantRefundId = merchantRefundId;
        this.amount = amount;
        this.reason = reason;
        this.refundedBy = refundedBy;
        this.claimedAt = claimedAt;
        this.claimToken = claimToken;
        this.status = RefundStatus.REQUESTED;
    }

    /**
     * 환불 선점(멱등키 선기록). status=REQUESTED로 생성한다. PG 취소 호출 전에 커밋해, 앱이 취소 도중 죽어도
     * "요청은 했다"는 기록이 남게 한다(청구의 PENDING 선기록과 같은 이유, SA §9-4).
     * 권한·상태·금액 검증은 Service에서 선행한다.
     *
     * <p>{@code claimToken}은 이 선점의 소유권 펜스다. 확정·실패 전이가 이 토큰을 조건으로 검사하므로,
     * 선점이 회수된 뒤 도착한 이전 요청의 결과는 반영되지 않는다.
     */
    public static PaymentRefund requested(
            Long paymentId, String merchantRefundId, int amount, String reason,
            Long refundedBy, LocalDateTime claimedAt, String claimToken) {
        return new PaymentRefund(paymentId, merchantRefundId, amount, reason,
                refundedBy, claimedAt, claimToken);
    }

    /** PG 취소 성공. 실제 전이는 조건부 UPDATE가 원자적으로 하고, 이 메서드는 테스트 픽스처·단위 검증용이다. */
    public void markCompleted(String pgCancelId, LocalDateTime refundedAt) {
        this.status = RefundStatus.COMPLETED;
        this.pgCancelId = pgCancelId;
        this.refundedAt = refundedAt;
        this.failureReason = null;
    }

    /** PG 취소 실패. payments는 PAID로 남으며 같은 멱등키로 재시도할 수 있다. */
    public void markFailed(String failureReason) {
        this.status = RefundStatus.FAILED;
        this.failureReason = failureReason;
    }
}
