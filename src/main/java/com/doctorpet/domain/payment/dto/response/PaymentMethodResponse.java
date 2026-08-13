package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.PaymentMethod;
import java.time.LocalDateTime;

/*
  결제수단 표시용 응답. 빌링키 원본·카드번호·CVC는 절대 포함하지 않고 표시 안전값(brand·last4)만 노출한다.
 */
public record PaymentMethodResponse(
        Long id,
        String cardBrand,
        String cardLast4,
        String status,
        boolean isDefault,
        LocalDateTime createdAt
) {

    public static PaymentMethodResponse from(PaymentMethod paymentMethod) {
        return new PaymentMethodResponse(
                paymentMethod.getId(),
                paymentMethod.getCardBrand(),
                paymentMethod.getCardLast4(),
                paymentMethod.getStatus().name(),
                paymentMethod.isDefaultPaymentMethod(),
                paymentMethod.getCreatedAt()
        );
    }
}
