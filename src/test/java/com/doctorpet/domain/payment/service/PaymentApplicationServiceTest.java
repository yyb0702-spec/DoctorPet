package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import com.doctorpet.global.gateway.payment.fake.FakePaymentGateway;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Level 1 — 외부 승인 결과·실패 원인별 분기 단위 검증(SA §9-4). 실제 PortOne 대신 FakePaymentGateway로
 * 성공/NON_RETRIABLE/RETRIABLE/UNKNOWN 시나리오를 주입해 상태 전이(PAID·OFFLINE_REQUIRED·PENDING)를 확인한다.
 * 트랜잭션 경계(PaymentChargeService)는 목으로 두고, 후확정에 전달되는 ChargeOutcome을 포획해 판정한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentApplicationServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long GUARDIAN_ID = 5L;
    private static final String MERCHANT_ID = "pay_x";
    private static final int AMOUNT = 50_000;
    private static final int MAX_RETRY = 3;

    @Mock private PaymentChargeService paymentChargeService;
    @Mock private BillingKeyCryptor billingKeyCryptor;
    @Mock private PaymentNotificationPublisher notificationPublisher;

    private FakePaymentGateway paymentGateway;
    private PaymentApplicationService paymentApplicationService;

    @BeforeEach
    void setUp() {
        paymentGateway = new FakePaymentGateway();
        // 백오프는 no-op으로 대체해 실제 대기 없이 재시도 분기를 검증한다.
        paymentApplicationService = new PaymentApplicationService(
                paymentChargeService, paymentGateway, billingKeyCryptor,
                notificationPublisher, attempt -> { }, MAX_RETRY);
    }

    private void stubPreRecord(boolean methodActive) {
        given(paymentChargeService.preRecord(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT)).willReturn(
                new PaymentPreRecord(PAYMENT_ID, MERCHANT_ID, "v1:enc", AMOUNT, methodActive, GUARDIAN_ID, RESERVATION_ID));
        // 후확정은 전달된 ChargeOutcome을 실제 상태에 반영한 Payment를 돌려줘 응답 status가 결과를 반영하게 한다.
        given(paymentChargeService.finalizeOutcome(anyLong(), any())).willAnswer(invocation -> {
            ChargeOutcome outcome = invocation.getArgument(1);
            Payment payment = Payment.pending(RESERVATION_ID, MERCHANT_ID, 7L, "VISA", "1234", AMOUNT);
            switch (outcome.type()) {
                case PAID -> payment.markPaid(outcome.pgPaymentId(), outcome.paidAt());
                case OFFLINE_REQUIRED -> payment.markOfflineRequired(outcome.failureReason(), outcome.retryCount());
                case PENDING -> payment.remainPending(outcome.retryCount(), outcome.failureReason());
            }
            return payment;
        });
    }

    private ChargeOutcome captureOutcome() {
        ArgumentCaptor<ChargeOutcome> captor = ArgumentCaptor.forClass(ChargeOutcome.class);
        verify(paymentChargeService).finalizeOutcome(eq(PAYMENT_ID), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("승인 성공이면 PAID로 확정하고 알림을 발행한다")
    void approveSuccess_paid() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");

        PaymentChargeResponse response = paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(captureOutcome().type()).isEqualTo(ChargeOutcome.Type.PAID);
        assertThat(paymentGateway.receivedMerchantPaymentIds()).containsExactly(MERCHANT_ID);
        verify(notificationPublisher).publishChargeResult(GUARDIAN_ID, RESERVATION_ID, null, PaymentStatus.PAID);
    }

    @Test
    @DisplayName("결제수단이 비활성이면 게이트웨이를 호출하지 않고 즉시 OFFLINE_REQUIRED로 확정한다")
    void inactiveMethod_offlineWithoutGateway() {
        stubPreRecord(false);

        PaymentChargeResponse response = paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.OFFLINE_REQUIRED);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.OFFLINE_REQUIRED);
        assertThat(outcome.failureReason()).isEqualTo("PAYMENT_METHOD_INACTIVE");
        assertThat(paymentGateway.receivedMerchantPaymentIds()).isEmpty();
    }

    @Test
    @DisplayName("재시도 무의미(NON_RETRIABLE) 실패는 재시도 없이 즉시 OFFLINE_REQUIRED로 확정한다")
    void nonRetriable_offlineImmediately() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        paymentGateway.stubApproveFailure(GatewayFailureReason.NON_RETRIABLE, "CARD_LIMIT", "한도 초과");

        paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.OFFLINE_REQUIRED);
        assertThat(outcome.failureReason()).isEqualTo("NON_RETRIABLE");
        // 재시도 없이 승인은 딱 1회만 시도한다.
        assertThat(paymentGateway.receivedMerchantPaymentIds()).hasSize(1);
    }

    @Test
    @DisplayName("재시도 소진 후 최종 단건조회로 미승인이 확인되면 OFFLINE_REQUIRED로 확정한다")
    void retriableExhaustedAndConfirmedNotPaid_offline() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        paymentGateway.stubApproveFailure(GatewayFailureReason.RETRIABLE, "TIMEOUT", "일시 장애");
        // 최종 조회가 FAILED(성공 아님 확인) → 소진 후 오프라인 전환.
        paymentGateway.stubQueryResult(MERCHANT_ID, GatewayPaymentStatus.FAILED, 0);

        paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.OFFLINE_REQUIRED);
        assertThat(outcome.failureReason()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(outcome.retryCount()).isEqualTo(MAX_RETRY);
        // 최초 1회 + 재시도 maxRetry회.
        assertThat(paymentGateway.receivedMerchantPaymentIds()).hasSize(1 + MAX_RETRY);
    }

    @Test
    @DisplayName("재시도 소진 후 최종 단건조회도 미확정이면 OFFLINE_REQUIRED가 아니라 PENDING을 유지한다(오프라인 이중수납 금지)")
    void retriableExhaustedButUnconfirmed_pending() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        // 승인은 계속 실패하고 최종 조회도 미확정(FakeGateway 기본 PENDING) — 승인 여부 미상.
        paymentGateway.stubApproveFailure(GatewayFailureReason.RETRIABLE, "TIMEOUT", "일시 장애");

        PaymentChargeResponse response = paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("RETRY_EXHAUSTED_UNCONFIRMED");
        assertThat(outcome.retryCount()).isEqualTo(MAX_RETRY);
    }

    @Test
    @DisplayName("타임아웃(UNKNOWN) 후 단건조회도 미확정이면 PENDING을 유지한다(정산 스케줄러가 확정)")
    void unknownThenUnconfirmed_pending() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        paymentGateway.stubApproveFailure(GatewayFailureReason.UNKNOWN, null, "응답 유실");

        PaymentChargeResponse response = paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(captureOutcome().type()).isEqualTo(ChargeOutcome.Type.PENDING);
    }

    @Test
    @DisplayName("타임아웃(UNKNOWN)이지만 단건조회 결과가 PAID면 재시도 없이 PAID로 확정한다(이미 승인됨)")
    void unknownButQueryPaid_paid() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        paymentGateway.stubApproveFailure(GatewayFailureReason.UNKNOWN, null, "응답 유실");
        // 승인 응답은 유실됐지만 실제로는 처리된 상황을 조회 결과로 주입.
        paymentGateway.stubQueryResult(MERCHANT_ID, GatewayPaymentStatus.PAID, AMOUNT);

        PaymentChargeResponse response = paymentApplicationService.charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(captureOutcome().type()).isEqualTo(ChargeOutcome.Type.PAID);
    }

    @Test
    @DisplayName("최초 승인이 PAID여도 승인 금액이 다르면 PAID로 확정하지 않고 PENDING을 유지한다(이미 승인됐을 수 있어 오프라인 금지)")
    void approvePaidWithAmountMismatch_pending() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        // FakeGateway는 요청 금액을 그대로 승인하므로, 금액 불일치는 mock 게이트웨이로 주입한다.
        PaymentGateway gateway = mock(PaymentGateway.class);
        given(gateway.approve(any())).willReturn(
                new PaymentApproveResult(GatewayPaymentStatus.PAID, "PG-1", AMOUNT + 1, LocalDateTime.now()));

        PaymentChargeResponse response = serviceWith(gateway).charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        // PG가 PAID를 반환했으므로 OFFLINE_REQUIRED(현장 수납)로 돌리면 이중결제 — PENDING 유지로 오프라인 정산을 막는다.
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("승인이 PAID여도 pgPaymentId가 비어 있으면 PAID로 확정하지 않고 PENDING을 유지한다(오프라인 금지)")
    void approvePaidWithBlankPgPaymentId_pending() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        PaymentGateway gateway = mock(PaymentGateway.class);
        given(gateway.approve(any())).willReturn(
                new PaymentApproveResult(GatewayPaymentStatus.PAID, "  ", AMOUNT, LocalDateTime.now()));

        PaymentChargeResponse response = serviceWith(gateway).charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("INVALID_PG_RESULT");
    }

    @Test
    @DisplayName("재시도 승인이 PAID여도 금액이 다르면 PENDING을 유지한다(재시도 경로도 오프라인 금지)")
    void retryApprovePaidWithAmountMismatch_pending() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        PaymentGateway gateway = mock(PaymentGateway.class);
        // 최초 승인은 RETRIABLE 실패 → 재시도 진입. 재시도의 단건조회는 미확정(PENDING), 재승인은 금액 불일치 PAID.
        given(gateway.approve(any()))
                .willThrow(new PaymentGatewayException(GatewayFailureReason.RETRIABLE, "TIMEOUT", "일시 장애"))
                .willReturn(new PaymentApproveResult(GatewayPaymentStatus.PAID, "PG-2", AMOUNT + 100, LocalDateTime.now()));
        given(gateway.query(any())).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0));

        PaymentChargeResponse response = serviceWith(gateway).charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    @DisplayName("단건조회 결과가 PAID여도 금액이 다르면 PENDING을 유지한다(조회 경로도 오프라인 금지)")
    void queryPaidWithAmountMismatch_pending() {
        stubPreRecord(true);
        given(billingKeyCryptor.decrypt("v1:enc")).willReturn("plain-key");
        PaymentGateway gateway = mock(PaymentGateway.class);
        // 최초 승인은 UNKNOWN(응답 유실) → 단건조회 우선. 조회는 PAID지만 금액 불일치 → 오프라인 금지, PENDING 유지.
        given(gateway.approve(any()))
                .willThrow(new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null, "응답 유실"));
        given(gateway.query(any())).willReturn(new PaymentQueryResult(GatewayPaymentStatus.PAID, "PG-Q", AMOUNT + 5));

        PaymentChargeResponse response = serviceWith(gateway).charge(RESERVATION_ID, STAFF_MEMBER_ID, AMOUNT);

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        ChargeOutcome outcome = captureOutcome();
        assertThat(outcome.type()).isEqualTo(ChargeOutcome.Type.PENDING);
        assertThat(outcome.failureReason()).isEqualTo("AMOUNT_MISMATCH");
    }

    private PaymentApplicationService serviceWith(PaymentGateway gateway) {
        return new PaymentApplicationService(
                paymentChargeService, gateway, billingKeyCryptor, notificationPublisher, attempt -> { }, MAX_RETRY);
    }
}
