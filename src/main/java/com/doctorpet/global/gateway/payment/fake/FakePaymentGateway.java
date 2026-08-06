package com.doctorpet.global.gateway.payment.fake;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 로컬·테스트용 결제 게이트웨이. 실제 PortOne 호출 없이 성공/실패/미확정 시나리오를 주입해
 * 상위 결제 서비스의 분기(재시도·오프라인 전환·조회)를 검증한다(이슈 #38 테스트 체크리스트).
 *
 * <p><b>안전한 기본값</b>: {@code payment.gateway=fake}로 <b>명시할 때만</b> 등록된다. 설정을 빠뜨리면
 * 이 빈이 생성되지 않으므로(fail-safe), 운영에서 설정 누락으로 Fake가 붙어 실제 청구 없이 {@code PAID}가
 * 반환되는 사고를 원천 차단한다. 운영은 {@code payment.gateway=portone}으로 PortOne 구현체를 쓴다.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "fake")
public class FakePaymentGateway implements PaymentGateway {

    /** 전달받은 멱등키 기록 — 멱등키 전달 검증용. 민감정보(빌링키·카드)는 저장하지 않는다. */
    private final List<String> receivedMerchantPaymentIds = new CopyOnWriteArrayList<>();

    /** 설정 시 approve가 이 예외를 던진다(실패 시나리오 주입). */
    private volatile PaymentGatewayException approveFailure;

    /** approve 성공 시 반환할 상태(기본 PAID). */
    private volatile GatewayPaymentStatus approveStatus = GatewayPaymentStatus.PAID;

    /** approve 결과를 멱등키별로 보관 — query가 승인 금액·상태를 그대로 재확정한다(단건 조회 금액 검증 시나리오용). */
    private final Map<String, PaymentApproveResult> approvedResults = new ConcurrentHashMap<>();

    /** 명시 주입된 단건 조회 결과 — 승인 응답 유실 후 조회로 재확정하는 시나리오용(approve 결과보다 우선). */
    private final Map<String, PaymentQueryResult> queryResults = new ConcurrentHashMap<>();

    /** 전달받은 취소 멱등키 기록 — 재시도가 같은 키를 재사용하는지 검증용(#37). */
    private final List<String> receivedMerchantRefundIds = new CopyOnWriteArrayList<>();

    /** 취소 결과를 멱등키별로 보관 — 같은 키 재요청이 첫 취소 결과를 그대로 반환하도록(실제 PG 멱등 모사). */
    private final Map<String, PaymentCancelResult> cancelledResults = new ConcurrentHashMap<>();

    /** cancel 도달 횟수. 멱등 흡수와 무관하게 실제 호출 수를 세, 동시 환불에서 PG 1회 호출을 검증한다. */
    private final AtomicInteger cancelCalls = new AtomicInteger();

    /** 설정 시 cancel이 이 예외를 던진다(취소 실패 시나리오 주입). */
    private volatile PaymentGatewayException cancelFailure;

    @Override
    public BillingKeyIssueResult verifyBillingKey(String billingKey) {
        return new BillingKeyIssueResult(true, "VISA", "1234");
    }

    @Override
    public PaymentApproveResult approve(PaymentApproveCommand command) {
        receivedMerchantPaymentIds.add(command.merchantPaymentId());
        if (approveFailure != null) {
            throw approveFailure;
        }
        // 멱등: 같은 merchantPaymentId 재요청은 첫 승인 결과를 그대로 반환한다(실제 PG 멱등 동작 모사).
        // 두 번째 요청의 금액이 달라도 첫 결과가 유지돼야 상위의 중복 승인·금액 대조 검증이 실제 계약과 일치한다.
        return approvedResults.computeIfAbsent(command.merchantPaymentId(), key ->
                new PaymentApproveResult(
                        approveStatus,
                        "FAKE-" + key,
                        command.amount(),
                        LocalDateTime.now()
                ));
    }

    @Override
    public PaymentQueryResult query(String merchantPaymentId) {
        // 1) 명시 주입된 조회 결과가 있으면 우선한다(승인 응답 유실 후 조회로 재확정하는 시나리오).
        PaymentQueryResult stubbed = queryResults.get(merchantPaymentId);
        if (stubbed != null) {
            return stubbed;
        }
        // 2) 없으면 보관된 승인 결과를 그대로 반영한다(상위의 금액 대조 검증용).
        PaymentApproveResult approved = approvedResults.get(merchantPaymentId);
        if (approved != null) {
            int paidAmount = approved.status() == GatewayPaymentStatus.PAID ? approved.approvedAmount() : 0;
            return new PaymentQueryResult(approved.status(), approved.pgPaymentId(), paidAmount);
        }
        // 3) 승인 이력도 없으면 외부 거래가 없으므로 PENDING·pgPaymentId null로 미확정을 나타낸다
        //    (존재하지 않는 결제를 승인건처럼 오판하지 않게 한다).
        return new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0);
    }

    /**
     * 전액 취소(#37). 실제 PG처럼 멱등키(merchantRefundId)별로 결과를 보관해, 같은 키 재요청은 첫 취소 결과를
     * 그대로 반환한다 — 상위 환불 재시도가 이중 취소를 일으키지 않는지 검증할 수 있게 한다.
     * 호출 횟수는 {@link #cancelCallCount()}로 확인한다(동시 환불에서 PG가 1번만 호출됐는지 검증용).
     */
    @Override
    public PaymentCancelResult cancel(PaymentCancelCommand command) {
        cancelCalls.incrementAndGet();
        receivedMerchantRefundIds.add(command.merchantRefundId());
        if (cancelFailure != null) {
            throw cancelFailure;
        }
        return cancelledResults.computeIfAbsent(command.merchantRefundId(), key ->
                new PaymentCancelResult("FAKE-CANCEL-" + key, command.amount(), LocalDateTime.now()));
    }

    // --- 테스트 시나리오 주입 API ---

    /** cancel이 실패하도록 설정한다. */
    public void stubCancelFailure(GatewayFailureReason reason, String providerErrorCode, String message) {
        this.cancelFailure = new PaymentGatewayException(reason, providerErrorCode, message);
    }

    /** cancel 실패 주입을 해제한다. */
    public void clearCancelFailure() {
        this.cancelFailure = null;
    }

    /**
     * cancel이 실제 취소 금액을 다르게 반환하도록 설정한다 — 상위의 취소 금액 대조 분기 검증용.
     * 같은 멱등키로 미리 결과를 심어두면 이후 cancel 호출이 이 값을 그대로 돌려준다.
     */
    public void stubCancelAmount(String merchantRefundId, int cancelledAmount) {
        cancelledResults.put(merchantRefundId,
                new PaymentCancelResult("FAKE-CANCEL-" + merchantRefundId, cancelledAmount, LocalDateTime.now()));
    }

    /** 총 cancel 호출 횟수(동시 환불에서 PG 호출이 1회인지 검증용). 멱등 흡수와 무관하게 도달 횟수를 센다. */
    public int cancelCallCount() {
        return cancelCalls.get();
    }

    /** 전달받은 취소 멱등키 목록(재시도가 같은 키를 재사용하는지 검증용). */
    public List<String> receivedMerchantRefundIds() {
        return List.copyOf(receivedMerchantRefundIds);
    }

    /** approve가 실패하도록 설정한다. */
    public void stubApproveFailure(GatewayFailureReason reason, String providerErrorCode, String message) {
        this.approveFailure = new PaymentGatewayException(reason, providerErrorCode, message);
    }

    /** approve가 특정 상태로 성공하도록 설정한다(예: PENDING = 미확정). */
    public void stubApproveStatus(GatewayPaymentStatus status) {
        this.approveFailure = null;
        this.approveStatus = status;
    }

    /**
     * 단건 조회 결과를 멱등키별로 직접 주입한다 — 승인 응답이 유실됐지만 실제로는 처리된 건을
     * 조회로 재확정하는 시나리오(SA §9-4)용. 상태·금액·pgPaymentId가 일관된 결과를 돌려준다.
     */
    public void stubQueryResult(String merchantPaymentId, GatewayPaymentStatus status, int paidAmount) {
        String pgPaymentId = status == GatewayPaymentStatus.PAID ? "FAKE-" + merchantPaymentId : null;
        int amount = status == GatewayPaymentStatus.PAID ? paidAmount : 0;
        queryResults.put(merchantPaymentId, new PaymentQueryResult(status, pgPaymentId, amount));
    }

    /** 마지막으로 전달받은 멱등키. */
    public String lastMerchantPaymentId() {
        return receivedMerchantPaymentIds.isEmpty()
                ? null
                : receivedMerchantPaymentIds.get(receivedMerchantPaymentIds.size() - 1);
    }

    /** 전달받은 멱등키 목록(중복 요청 확인용). */
    public List<String> receivedMerchantPaymentIds() {
        return List.copyOf(receivedMerchantPaymentIds);
    }

    /** 시나리오·기록 초기화. */
    public void reset() {
        receivedMerchantPaymentIds.clear();
        approvedResults.clear();
        queryResults.clear();
        approveFailure = null;
        approveStatus = GatewayPaymentStatus.PAID;
        receivedMerchantRefundIds.clear();
        cancelledResults.clear();
        cancelCalls.set(0);
        cancelFailure = null;
    }
}
