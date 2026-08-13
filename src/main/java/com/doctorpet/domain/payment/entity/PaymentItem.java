package com.doctorpet.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/*
  진료비 청구 항목(SA §4 payment_items, SA §9-4 "청구 항목").
  - reservation_id·payment_id는 도메인 경계를 넘는 참조가 아니어도 Payment·PaymentRefund와 같은 규칙으로
    @ManyToOne이 아니라 FK 값(Long)으로 둔다(가드레일·SA §4).
  - payment_id가 NULL이면 "아직 청구되지 않은 초안"이다. 항목은 청구 선기록 전에 작성·수정되므로 그 시점에는
    payments 행이 없어 결제에 매달 수 없다. 그래서 예약에 매달린 초안으로 만들고, 청구 선기록이 같은
    트랜잭션에서 payment_id를 스탬프해 청구 시점 스냅샷을 고정한다(SA §4 payment_items).
  - 스탬프 이후에는 절대 수정·삭제하지 않는다. 이 금지는 스냅샷 정합성뿐 아니라 정정 재청구를 우회 구현하지
    못하게 하는 경계이므로, 수정·삭제는 WHERE payment_id IS NULL 조건부 쓰기로만 수행한다(SA §9-4).
    엔티티에 수정 메서드를 두지 않는 것도 같은 이유다.
  - unit_price·amount는 할인·조정 항목(예: -5000원 쿠폰)을 음수 금액 항목으로 표현해야 하므로 음수를 허용하는
    signed 정수다. MySQL DDL에서 이 두 컬럼을 UNSIGNED로 만들면 음수 조정 저장이 막히므로,
    PaymentItemConstraintMigrationRunner가 signed임을 검증한다(SA §4).
  - updated_at을 두지 않는다(SA §4 payment_items 컬럼 표). 초안은 전체 교체로 갱신하고 스탬프는 조건부 bulk
    UPDATE라 Hibernate 감사 콜백을 타지 않으므로, updated_at이 있어도 실제 변경 시각을 말해주지 못한다.
 */
@Getter
@Entity
@Table(
        name = "payment_items",
        indexes = {
                // 초안 조회와 청구 시 스탬프 대상 조회가 (reservation_id, payment_id IS NULL)로 접근한다.
                @Index(name = "idx_payment_items_reservation_id_payment_id",
                        columnList = "reservation_id, payment_id"),
                // 영수증 조회가 결제 1건의 항목을 통째로 읽는다.
                @Index(name = "idx_payment_items_payment_id", columnList = "payment_id")
        }
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 항목이 매달린 예약. 청구 전 초안도 이 값으로 존재한다.
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    // 청구 선기록이 스탬프하는 소속 결제. NULL이 "아직 청구되지 않은 초안"이다.
    @Column(name = "payment_id")
    private Long paymentId;

    // 병원 스태프가 입력한 항목명(예: "초진 진찰료"). 표시용 스냅샷이라 이후 마스터가 바뀌어도 그대로 남는다.
    @Column(nullable = false, length = 100)
    private String name;

    // 수량. 양수만 허용한다(요청 DTO @Positive가 1차, 서비스 검증이 2차 방어선, SA §9-4).
    @Column(nullable = false)
    private int quantity;

    // 단가(원). 할인·조정 항목을 위해 음수를 허용한다.
    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    // 항목 금액(원) = quantity * unitPrice. 요청에서 받지 않고 서버가 산출한다(SA §9-4).
    @Column(nullable = false)
    private int amount;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    private PaymentItem(Long reservationId, String name, int quantity, int unitPrice, int amount) {
        this.reservationId = reservationId;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
    }

    /**
     * 청구 전 초안 항목을 만든다({@code payment_id IS NULL}). 금액 계산·범위 검증은 서비스가 선행하고,
     * 여기서는 계산 결과가 {@code quantity * unitPrice}와 어긋나지 않는지만 마지막으로 확인한다
     * (DB CHECK 제약과 같은 불변식).
     */
    public static PaymentItem draft(Long reservationId, String name, int quantity, int unitPrice, int amount) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("항목 수량은 0보다 커야 합니다. quantity=" + quantity);
        }
        if ((long) quantity * unitPrice != amount) {
            throw new IllegalArgumentException("항목 금액이 수량×단가와 다릅니다. amount=" + amount);
        }
        return new PaymentItem(reservationId, name, quantity, unitPrice, amount);
    }

    /** 아직 청구되지 않은 초안인지. 실제 스탬프는 조건부 bulk UPDATE가 하고 이 메서드는 조회·검증용이다. */
    public boolean isDraft() {
        return this.paymentId == null;
    }
}
