package com.doctorpet.global.gateway.payment.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentApproveCommandTest {

    @Test
    @DisplayName("toString은 빌링키 원본을 노출하지 않고 마스킹한다")
    void toStringMasksBillingKey() {
        PaymentApproveCommand command =
                new PaymentApproveCommand("mpid-1", "billingkey_raw_secret_value", 50000, "진료비");

        String text = command.toString();

        assertFalse(text.contains("billingkey_raw_secret_value"), "빌링키 원본이 toString에 남으면 안 된다");
        assertFalse(text.contains("raw_secret_value"), "빌링키 값 일부라도 toString에 남으면 안 된다");
        assertTrue(text.contains("****"), "마스킹된 빌링키 형태여야 한다");
        assertTrue(text.contains("mpid-1"), "멱등키는 추적을 위해 유지한다");
        assertTrue(text.contains("50000"), "금액은 민감정보가 아니므로 유지한다");
    }
}
