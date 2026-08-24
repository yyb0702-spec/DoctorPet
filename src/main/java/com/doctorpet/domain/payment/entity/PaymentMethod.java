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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
  보호자 결제수단(빌링키). SA §4 payment_methods 스펙을 따른다.
  - 빌링키 원본·카드번호·CVC는 저장하지 않는다. billing_key_enc(암호문)와 표시용 brand·last4만 보관한다.
  - 회원 참조는 도메인 경계를 넘으므로 @ManyToOne이 아니라 FK 값(memberId Long)으로 둔다(구현 가드레일).
  - 삭제는 소프트 삭제(status=DELETED). id는 등록 후 예약이 참조하므로 재사용·불변이다(SA §9-4).
 */
@Getter
@Entity
@Table(name = "payment_methods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentMethod extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // AES-256-GCM 암호문(버전 접두 + Base64). BillingKeyCryptor로만 암복호화한다.
    @Column(name = "billing_key_enc", nullable = false, length = 1000)
    private String billingKeyEnc;

    // 표시·#34 스냅샷 소스. 게이트웨이가 반환한 안전값만 저장한다(파생값이 아닌 저장 컬럼).
    @Column(name = "card_brand", length = 40)
    private String cardBrand;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethodStatus status;

    @Column(name = "is_default", nullable = false, columnDefinition = "boolean not null default false")
    private boolean defaultPaymentMethod;

    private PaymentMethod(
            Long memberId,
            String billingKeyEnc,
            String cardBrand,
            String cardLast4,
            boolean defaultPaymentMethod
    ) {
        this.memberId = memberId;
        this.billingKeyEnc = billingKeyEnc;
        this.cardBrand = cardBrand;
        this.cardLast4 = cardLast4;
        this.status = PaymentMethodStatus.ACTIVE;
        this.defaultPaymentMethod = defaultPaymentMethod;
    }

    /** 검증된 빌링키로 결제수단을 등록한다(status=ACTIVE). */
    public static PaymentMethod issue(Long memberId, String billingKeyEnc, String cardBrand, String cardLast4) {
        return new PaymentMethod(memberId, billingKeyEnc, cardBrand, cardLast4, false);
    }

    /** 첫 결제수단 후보로 저장한다. DB UNIQUE 제약이 회원당 최종 1건만 기본값으로 확정한다. */
    public static PaymentMethod issueAsDefault(
            Long memberId,
            String billingKeyEnc,
            String cardBrand,
            String cardLast4
    ) {
        return new PaymentMethod(memberId, billingKeyEnc, cardBrand, cardLast4, true);
    }

    /** 소프트 삭제. 물리 삭제하지 않고 상태만 DELETED로 전이한다(SA §4-2). */
    public void markDeleted() {
        this.status = PaymentMethodStatus.DELETED;
        this.defaultPaymentMethod = false;
    }

    public void markDefault() {
        this.defaultPaymentMethod = true;
    }

    public void clearDefault() {
        this.defaultPaymentMethod = false;
    }
}
