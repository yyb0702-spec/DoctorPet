package com.doctorpet.global.gateway.payment.fake;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakePaymentGatewayTest {

    private FakePaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new FakePaymentGateway();
    }

    @Test
    @DisplayName("기본 시나리오는 PAID로 승인하고 요청 금액을 그대로 반영한다")
    void approveSuccess() {
        PaymentApproveResult result = gateway.approve(
                new PaymentApproveCommand("mpid-1", "billingkey_secret", 50000, "진료비"));

        assertEquals(GatewayPaymentStatus.PAID, result.status());
        assertEquals(50000, result.approvedAmount());
        assertTrue(result.pgPaymentId().contains("mpid-1"));
    }

    @Test
    @DisplayName("전달한 멱등키가 게이트웨이에 그대로 전달된다")
    void merchantPaymentIdPassedThrough() {
        gateway.approve(new PaymentApproveCommand("mpid-abc", "bk", 1000, "진료비"));

        assertEquals("mpid-abc", gateway.lastMerchantPaymentId());
        assertTrue(gateway.receivedMerchantPaymentIds().contains("mpid-abc"));
    }

    @Test
    @DisplayName("실패 시나리오 주입 시 재시도 성격을 담은 예외를 던진다")
    void approveFailure() {
        gateway.stubApproveFailure(GatewayFailureReason.NON_RETRIABLE, "CARD_LIMIT_EXCEEDED", "한도 초과");

        PaymentGatewayException ex = assertThrows(PaymentGatewayException.class,
                () -> gateway.approve(new PaymentApproveCommand("mpid-2", "bk", 1000, "진료비")));

        assertEquals(GatewayFailureReason.NON_RETRIABLE, ex.getFailureReason());
        assertEquals("CARD_LIMIT_EXCEEDED", ex.getProviderErrorCode());
    }

    @Test
    @DisplayName("미확정(PENDING) 승인 시나리오를 주입할 수 있다")
    void approvePending() {
        gateway.stubApproveStatus(GatewayPaymentStatus.PENDING);

        PaymentApproveResult result = gateway.approve(
                new PaymentApproveCommand("mpid-3", "bk", 1000, "진료비"));

        assertEquals(GatewayPaymentStatus.PENDING, result.status());
    }

    @Test
    @DisplayName("승인 응답 유실 후 단건 조회로 PAID·금액을 재확정한다(SA §9-4 타임아웃 분기)")
    void queryReconfirmsAfterLostApproval() {
        // 승인 응답 유실(UNKNOWN) — approve는 아무것도 저장하지 못한다
        gateway.stubApproveFailure(GatewayFailureReason.UNKNOWN, null, "응답 유실");
        assertThrows(PaymentGatewayException.class,
                () -> gateway.approve(new PaymentApproveCommand("mpid-4", "bk", 5000, "진료비")));

        // 실제로는 처리된 건을 단건 조회로 재확정 — 상태·금액·식별자가 일관되게 주입된다
        gateway.stubQueryResult("mpid-4", GatewayPaymentStatus.PAID, 5000);
        PaymentQueryResult result = gateway.query("mpid-4");

        assertEquals(GatewayPaymentStatus.PAID, result.status());
        assertEquals(5000, result.paidAmount(), "상위 서비스가 요청 금액과 대조할 수 있어야 한다");
        assertTrue(result.pgPaymentId().contains("mpid-4"));
    }

    @Test
    @DisplayName("승인 후 단건 조회는 승인된 금액·상태를 그대로 반영한다(상위의 금액 대조 검증용)")
    void queryReflectsApprovedAmount() {
        gateway.approve(new PaymentApproveCommand("mpid-amt", "bk", 73000, "진료비"));

        PaymentQueryResult result = gateway.query("mpid-amt");

        assertEquals(GatewayPaymentStatus.PAID, result.status());
        assertEquals(73000, result.paidAmount());
        assertTrue(result.pgPaymentId().contains("mpid-amt"));
    }

    @Test
    @DisplayName("승인 이력이 없는 멱등키 조회는 PENDING·금액 0·pgPaymentId null로 미확정을 나타낸다")
    void queryUnknownIsPending() {
        PaymentQueryResult result = gateway.query("mpid-never");

        assertEquals(GatewayPaymentStatus.PENDING, result.status());
        assertEquals(0, result.paidAmount());
        assertNull(result.pgPaymentId(), "존재하지 않는 결제는 외부 식별자가 없어야 한다");
    }

    @Test
    @DisplayName("같은 멱등키 재승인은 첫 승인 결과를 유지한다(금액이 달라도 첫 결과 반환)")
    void approveIsIdempotentPerMerchantPaymentId() {
        PaymentApproveResult first = gateway.approve(
                new PaymentApproveCommand("mpid-dup", "bk", 1000, "진료비"));
        PaymentApproveResult second = gateway.approve(
                new PaymentApproveCommand("mpid-dup", "bk", 2000, "진료비"));

        assertEquals(1000, first.approvedAmount());
        assertEquals(1000, second.approvedAmount(), "재요청 금액(2000)이 아니라 첫 승인 금액(1000)이 유지돼야 한다");
        assertEquals(1000, gateway.query("mpid-dup").paidAmount());
    }

    @Test
    @DisplayName("빌링키 검증은 저장·표시에 안전한 값만 반환하고 원본을 되돌려주지 않는다")
    void verifyBillingKeyReturnsSafeValues() {
        BillingKeyIssueResult result = gateway.verifyBillingKey("billingkey_raw_secret");

        assertTrue(result.valid());
        assertEquals("VISA", result.cardBrand());
        assertEquals("1234", result.cardLast4());
        assertFalse("billingkey_raw_secret".equals(result.cardBrand()));
    }

    @Test
    @DisplayName("취소는 요청 금액을 그대로 반영하고 취소 멱등키를 기록한다")
    void cancelSuccess() {
        PaymentCancelResult result = gateway.cancel(
                new PaymentCancelCommand("mpid-5", "rfd-5", 50000, "오청구"));

        assertEquals(50000, result.amount());
        assertTrue(result.pgCancelId().contains("rfd-5"));
        assertEquals(List.of("rfd-5"), gateway.receivedMerchantRefundIds());
        assertEquals(1, gateway.cancelCallCount());
    }

    @Test
    @DisplayName("같은 취소 멱등키 재요청은 첫 취소 결과를 그대로 반환한다(이중 취소 없음)")
    void cancelIsIdempotentPerRefundKey() {
        PaymentCancelResult first = gateway.cancel(
                new PaymentCancelCommand("mpid-6", "rfd-6", 50000, "오청구"));
        // 두 번째 요청의 금액이 달라도 첫 취소 결과가 유지돼야 실제 PG 멱등 계약과 일치한다.
        PaymentCancelResult second = gateway.cancel(
                new PaymentCancelCommand("mpid-6", "rfd-6", 30000, "오청구"));

        assertEquals(first.pgCancelId(), second.pgCancelId());
        assertEquals(50000, second.amount());
        // 호출 도달 횟수는 2회로 세되(상위의 중복 호출 검증용), 취소 결과는 1건으로 유지된다.
        assertEquals(2, gateway.cancelCallCount());
    }

    @Test
    @DisplayName("취소 실패를 주입하면 분류된 게이트웨이 예외를 던진다")
    void cancelFailure() {
        gateway.stubCancelFailure(GatewayFailureReason.NON_RETRIABLE, "CANCELLABLE_AMOUNT_CONSUMED", "취소 불가");

        PaymentGatewayException e = assertThrows(PaymentGatewayException.class,
                () -> gateway.cancel(new PaymentCancelCommand("mpid-7", "rfd-7", 50000, "오청구")));

        assertEquals(GatewayFailureReason.NON_RETRIABLE, e.getFailureReason());
        // 실패해도 호출 도달은 기록된다 — 상위가 "PG를 몇 번 불렀는지"로 멱등을 검증하기 때문이다.
        assertEquals(1, gateway.cancelCallCount());
    }

    @Test
    @DisplayName("reset은 취소 기록·시나리오까지 초기화한다")
    void resetClearsCancelState() {
        gateway.cancel(new PaymentCancelCommand("mpid-8", "rfd-8", 50000, "오청구"));
        gateway.stubCancelFailure(GatewayFailureReason.RETRIABLE, "PG_TIMEOUT", "일시 장애");

        gateway.reset();

        assertEquals(0, gateway.cancelCallCount());
        assertTrue(gateway.receivedMerchantRefundIds().isEmpty());
        // 실패 주입이 남아 있으면 이후 테스트가 오염된다.
        assertEquals(50000, gateway.cancel(
                new PaymentCancelCommand("mpid-8", "rfd-8", 50000, "오청구")).amount());
    }
}
