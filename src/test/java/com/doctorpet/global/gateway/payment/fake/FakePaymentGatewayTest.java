package com.doctorpet.global.gateway.payment.fake;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
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
    @DisplayName("승인 후 상태를 주입해 타임아웃 뒤 단건 조회로 재확정하는 시나리오를 만든다")
    void queryStatus() {
        gateway.approve(new PaymentApproveCommand("mpid-4", "bk", 5000, "진료비"));
        gateway.stubQueryStatus(GatewayPaymentStatus.PAID);

        PaymentQueryResult result = gateway.query("mpid-4");

        assertEquals(GatewayPaymentStatus.PAID, result.status());
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
    @DisplayName("취소·환불은 MVP 확장 지점으로 미지원 예외를 던진다")
    void cancelUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> gateway.cancel(new PaymentCancelCommand("mpid-5", 1000, "테스트")));
    }
}
