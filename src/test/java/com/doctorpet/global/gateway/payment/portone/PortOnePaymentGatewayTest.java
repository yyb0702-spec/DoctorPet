package com.doctorpet.global.gateway.payment.portone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — PortOne V2 게이트웨이 단위 검증(#57). 설정 가드·오류 분류와, HttpClient를 목으로 주입한
 * 요청/응답 바인딩(토큰 발급 후 상태 매핑·금액 대조·오류 분류)을 확인한다. 실 승인 e2e(테스트 상점)는 별도.
 */
class PortOnePaymentGatewayTest {

    private static final String TOKEN_RESPONSE = "{\"accessToken\":\"tok_test\"}";

    private final PortOneErrorCodeMapper errorCodeMapper = new PortOneErrorCodeMapper();

    private PortOneProperties configuredProperties() {
        PortOneProperties props = new PortOneProperties();
        props.setBaseUrl("https://api.portone.io");
        props.setApiSecret("test-secret");
        props.setStoreId("store-test");
        props.setChannelKey("channel-key-test");
        return props;
    }

    // --- 설정 가드 · 오류 분류 ---

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
        props.setBaseUrl("https://api.portone.io");
        props.setApiSecret("test-secret");
        // store-id 미설정

        assertThrows(IllegalStateException.class,
                () -> new PortOnePaymentGateway(props, errorCodeMapper));
    }

    @Test
    @DisplayName("channel-key가 누락되면 생성 시점에 실패한다")
    void requireChannelKey() {
        PortOneProperties props = new PortOneProperties();
        props.setBaseUrl("https://api.portone.io");
        props.setApiSecret("test-secret");
        props.setStoreId("store-test");
        // channel-key 미설정

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

    // --- 실 요청/응답 바인딩 (HttpClient 목 주입) ---

    private HttpClient httpClient;

    private PortOnePaymentGateway gatewayWith(HttpResponse<String> operationResponse) throws Exception {
        httpClient = mock(HttpClient.class);
        // 토큰 응답 목은 send 스텁 이전에 만든다 — willReturn 인자 안에서 response()가 또 given()을 호출하면
        // Mockito 중첩 스텁(UnfinishedStubbingException)이 된다.
        HttpResponse<String> tokenResponse = response(200, TOKEN_RESPONSE);
        // 각 오퍼레이션은 토큰 발급(1) + 실제 호출(1)로 2번 send 한다. 순서대로 반환값을 스텁한다.
        // BodyHandler 매처를 String으로 고정해 send의 제네릭 T가 Object로 추론되는 것을 막는다.
        given(httpClient.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .willReturn(tokenResponse, operationResponse);
        return new PortOnePaymentGateway(configuredProperties(), errorCodeMapper, httpClient, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        given(response.statusCode()).willReturn(status);
        given(response.body()).willReturn(body);
        return response;
    }

    @Test
    @DisplayName("빌링키가 ISSUED면 유효로 보고 카드 brand·last4를 스냅샷용으로 반환한다")
    void verifyBillingKey_issued() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(200,
                "{\"status\":\"ISSUED\",\"methods\":[{\"type\":\"CARD\",\"card\":{\"brand\":\"MASTER\",\"number\":\"433012******1234\"}}]}"));

        BillingKeyIssueResult result = gateway.verifyBillingKey("billing-key-1");

        assertThat(result.valid()).isTrue();
        assertThat(result.cardBrand()).isEqualTo("MASTER");
        assertThat(result.cardLast4()).isEqualTo("1234");
    }

    @Test
    @DisplayName("존재하지 않는 빌링키(404)는 예외가 아니라 valid=false로 반환한다")
    void verifyBillingKey_notFound() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(404, ""));

        assertThat(gateway.verifyBillingKey("missing").valid()).isFalse();
    }

    @Test
    @DisplayName("삭제된 빌링키(status=DELETED)는 valid=false로 반환한다")
    void verifyBillingKey_deleted() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(200, "{\"status\":\"DELETED\"}"));

        assertThat(gateway.verifyBillingKey("deleted").valid()).isFalse();
    }

    @Test
    @DisplayName("빌링키 결제가 PAID면 상태·pg식별자·승인금액·승인시각을 매핑해 반환한다")
    void approve_paid() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(200,
                "{\"payment\":{\"id\":\"pay_1\",\"status\":\"PAID\",\"pgTxId\":\"pgtx_1\","
                        + "\"amount\":{\"total\":50000},\"paidAt\":\"2026-08-04T10:00:00+09:00\"}}"));

        PaymentApproveResult result = gateway.approve(
                new PaymentApproveCommand("pay_1", "billing-key-1", 50000, "DoctorPet 진료비"));

        assertThat(result.status()).isEqualTo(GatewayPaymentStatus.PAID);
        assertThat(result.pgPaymentId()).isEqualTo("pgtx_1");
        assertThat(result.approvedAmount()).isEqualTo(50000);
        assertThat(result.approvedAt()).isEqualTo(LocalDateTime.of(2026, 8, 4, 10, 0));
    }

    @Test
    @DisplayName("승인 요청은 merchantPaymentId를 Idempotency-Key 헤더로 실어 재시도 시 이중 승인을 막는다")
    void approve_sendsIdempotencyKey() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(200,
                "{\"payment\":{\"id\":\"pay_1\",\"status\":\"PAID\",\"amount\":{\"total\":50000}}}"));

        gateway.approve(new PaymentApproveCommand("pay_1", "billing-key-1", 50000, "DoctorPet 진료비"));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        // 토큰 발급(1) + 승인(1)로 2번 send 한다. 빌링키 결제 요청을 골라 헤더를 확인한다.
        verify(httpClient, times(2)).send(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        HttpRequest approveRequest = captor.getAllValues().stream()
                .filter(req -> req.uri().getPath().endsWith("/billing-key"))
                .findFirst()
                .orElseThrow();
        assertThat(approveRequest.headers().firstValue("Idempotency-Key")).contains("pay_1");
    }

    @Test
    @DisplayName("UTC(Z) 승인 시각은 offset을 버리지 않고 서울 시각으로 변환한다")
    void approve_paidAtUtcConvertedToSeoul() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(200,
                "{\"payment\":{\"id\":\"pay_1\",\"status\":\"PAID\",\"amount\":{\"total\":50000},"
                        + "\"paidAt\":\"2026-08-04T01:00:00Z\"}}"));

        PaymentApproveResult result = gateway.approve(
                new PaymentApproveCommand("pay_1", "billing-key-1", 50000, "DoctorPet 진료비"));

        // 01:00Z == 서울 10:00. toLocalDateTime()만 썼다면 01:00으로 9시간 틀어진다.
        assertThat(result.approvedAt()).isEqualTo(LocalDateTime.of(2026, 8, 4, 10, 0));
    }

    @Test
    @DisplayName("빌링키 결제 실패(4xx + 공급자 코드)는 재시도 성격으로 분류한 PaymentGatewayException을 던진다")
    void approve_failure_classified() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(402, "{\"type\":\"PG_TIMEOUT\",\"message\":\"일시 장애\"}"));

        assertThatThrownBy(() -> gateway.approve(
                new PaymentApproveCommand("pay_1", "billing-key-1", 50000, "DoctorPet 진료비")))
                .isInstanceOfSatisfying(PaymentGatewayException.class, ex -> {
                    assertThat(ex.getFailureReason()).isEqualTo(GatewayFailureReason.RETRIABLE);
                    assertThat(ex.getProviderErrorCode()).isEqualTo("PG_TIMEOUT");
                });
    }

    @Test
    @DisplayName("결제 조회가 PAID면 상태와 결제 금액을 반환한다")
    void query_paid() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(
                response(200, "{\"status\":\"PAID\",\"pgTxId\":\"pgtx_9\",\"amount\":{\"total\":50000}}"));

        PaymentQueryResult result = gateway.query("pay_1");

        assertThat(result.status()).isEqualTo(GatewayPaymentStatus.PAID);
        assertThat(result.paidAmount()).isEqualTo(50000);
    }

    @Test
    @DisplayName("결제 기록이 없으면(404) FAILED로 단정하지 않고 PENDING(미확정)으로 반환한다")
    void query_notFound_pending() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(404, ""));

        PaymentQueryResult result = gateway.query("missing");

        assertThat(result.status()).isEqualTo(GatewayPaymentStatus.PENDING);
        assertThat(result.paidAmount()).isZero();
    }

    @Test
    @DisplayName("서버 오류(5xx, 코드 없음)는 HTTP 상태로 보조 분류해 RETRIABLE 예외를 던진다")
    void query_serverError_retriable() throws Exception {
        PortOnePaymentGateway gateway = gatewayWith(response(500, "{}"));

        assertThatThrownBy(() -> gateway.query("pay_1"))
                .isInstanceOfSatisfying(PaymentGatewayException.class,
                        ex -> assertThat(ex.getFailureReason()).isEqualTo(GatewayFailureReason.RETRIABLE));
    }
}
