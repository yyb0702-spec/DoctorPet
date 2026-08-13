package com.doctorpet.domain.payment.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
  진료비 청구 항목(고도화 결제 3.1). Payment 1건에 1..N행이며, 청구 시작(PENDING 선기록)과 같은 트랜잭션에서만 저장한다.
  - payment_id는 Payment·PaymentRefund와 같은 규칙으로 @ManyToOne이 아니라 FK 값(Long)으로 둔다(가드레일·SA §4).
  - 청구 시점 스냅샷이라 청구 시작 후 수정하는 경로를 두지 않는다 — 수정 메서드도, 수정 API도 없다.
  - unitPrice·amount는 할인·조정 항목(예: -5000원 쿠폰)을 표현해야 하므로 음수를 허용하는 signed 정수다.
    MySQL DDL에서 UNSIGNED를 쓰면 할인 항목을 저장할 수 없어지므로, 마이그레이션 러너가 signed임을 검증한다.
  - quantity > 0과 amount = quantity * unitPrice는 애플리케이션 검증뿐 아니라 DB CHECK 제약으로도 못박는다
    (PaymentItemConstraintMigrationRunner). Payment.amount = 항목 amount 합계는 테이블을 넘는 관계라 DB로 강제할 수
    없어 청구 서비스가 계산·검증하고 Level 3 통합 테스트가 정합을 확인한다.
 */
@Getter
@Entity
@Table(
        name = "payment_items",
        // 영수증·내역 조회가 결제 1건의 항목을 통째로 읽으므로 payment_id 단일 인덱스를 둔다.
        indexes = @Index(name = "idx_payment_items_payment_id", columnList = "payment_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    // 병원 스태프가 입력한 항목명(예: "초진 진찰료"). 표시용 스냅샷이라 이후 마스터가 바뀌어도 그대로 남는다.
    @Column(nullable = false, length = 100)
    private String name;

    // 수량. 양수만 허용한다(0·음수는 청구 서비스가 거부하고 DB CHECK가 최종 방어).
    @Column(nullable = false)
    private int quantity;

    // 단가(원). 할인·조정 항목을 위해 음수를 허용한다.
    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    // 항목 금액(원) = quantity * unitPrice. 단가와 같은 이유로 음수를 허용한다.
    @Column(nullable = false)
    private int amount;

    private PaymentItem(Long paymentId, String name, int quantity, int unitPrice, int amount) {
        this.paymentId = paymentId;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
    }

    /**
     * 청구 시점 항목 스냅샷을 만든다. 금액 계산·범위 검증은 청구 서비스가 선행하고, 여기서는 계산 결과가
     * {@code quantity * unitPrice}와 어긋나지 않는지만 마지막으로 확인한다(DB CHECK와 같은 불변식).
     */
    public static PaymentItem snapshot(Long paymentId, String name, int quantity, int unitPrice, int amount) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("항목 수량은 0보다 커야 합니다. quantity=" + quantity);
        }
        if ((long) quantity * unitPrice != amount) {
            throw new IllegalArgumentException("항목 금액이 수량×단가와 다릅니다. amount=" + amount);
        }
        return new PaymentItem(paymentId, name, quantity, unitPrice, amount);
    }
}
