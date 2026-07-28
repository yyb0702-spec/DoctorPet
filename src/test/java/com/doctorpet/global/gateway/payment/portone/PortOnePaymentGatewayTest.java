package com.doctorpet.global.gateway.payment.portone;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortOnePaymentGatewayTest {

    private final PortOneErrorCodeMapper errorCodeMapper = new PortOneErrorCodeMapper();

    private PortOneProperties configuredProperties() {
        PortOneProperties props = new PortOneProperties();
        props.setBaseUrl("https://example.invalid");
        props.setApiSecret("test-secret");
        props.setStoreId("store-1");
        return props;
    }

    @Test
    @DisplayName("설정이 없으면 생성 시점에 IllegalStateException을 던진다")
    void requireConfiguration() {
        PortOneProperties empty = new PortOneProperties();

        assertThrows(IllegalStateException.class,
                () -> new PortOnePaymentGateway(empty, errorCodeMapper));
    }

    @Test
    @DisplayName("store-id가 누락되면 생성 시점에 실패한다")
    void requireStoreId() {
        PortOneProperties props = new PortOneProperties();
        props.setBaseUrl("https://example.invalid");
        props.setApiSecret("test-secret");
        // store-id 미설정

        assertThrows(IllegalStateException.class,
                () -> new PortOnePaymentGateway(props, errorCodeMapper));
    }

    @Test
    @DisplayName("공급자 오류 코드를 재시도 성격으로 변환한다")
    void translateByProviderCode() {
        PortOnePaymentGateway gateway = new PortOnePaymentGateway(configuredProperties(), errorCodeMapper);

        PaymentGatewayException ex = gateway.translate("BILLING_KEY_EXPIRED", 400, "만료", null);

        assertEquals(GatewayFailureReason.NON_RETRIABLE, ex.getFailureReason());
        assertEquals("BILLING_KEY_EXPIRED", ex.getProviderErrorCode());
    }

    @Test
    @DisplayName("공급자 코드가 없으면 HTTP 상태로 보조 분류한다")
    void translateByHttpStatus() {
        PortOnePaymentGateway gateway = new PortOnePaymentGateway(configuredProperties(), errorCodeMapper);

        PaymentGatewayException ex = gateway.translate(null, 503, "일시 장애", null);

        assertEquals(GatewayFailureReason.RETRIABLE, ex.getFailureReason());
    }

    @Test
    @DisplayName("실 요청/응답 바인딩은 실연동(B-1) 전까지 미지원 예외로 경계를 알린다")
    void approveIntegrationPending() {
        PortOnePaymentGateway gateway = new PortOnePaymentGateway(configuredProperties(), errorCodeMapper);

        assertThrows(UnsupportedOperationException.class,
                () -> gateway.approve(new PaymentApproveCommand("mpid-1", "bk", 1000, "진료비")));
    }
}
