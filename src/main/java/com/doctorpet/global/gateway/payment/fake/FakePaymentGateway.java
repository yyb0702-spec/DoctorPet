package com.doctorpet.global.gateway.payment.fake;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /** query 상태 강제 오버라이드(null이면 보관된 approve 결과를 반영, 없으면 PENDING). */
    private volatile GatewayPaymentStatus queryStatusOverride;

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
        PaymentApproveResult approved = approvedResults.get(merchantPaymentId);
        GatewayPaymentStatus status = queryStatusOverride != null
                ? queryStatusOverride
                : (approved != null ? approved.status() : GatewayPaymentStatus.PENDING);
        // 조회 금액은 승인된 경우에만 보관된 실제 승인 금액을 돌려준다(상위의 금액 대조 검증용).
        int paidAmount = (status == GatewayPaymentStatus.PAID && approved != null) ? approved.approvedAmount() : 0;
        // 승인 이력이 없으면 외부 거래가 없으므로 pgPaymentId도 null이다(PaymentQueryResult 계약).
        // 존재하지 않는 결제를 승인건처럼 오판하지 않게 한다.
        String pgPaymentId = approved != null ? approved.pgPaymentId() : null;
        return new PaymentQueryResult(status, pgPaymentId, paidAmount);
    }

    // --- 테스트 시나리오 주입 API ---

    /** approve가 실패하도록 설정한다. */
    public void stubApproveFailure(GatewayFailureReason reason, String providerErrorCode, String message) {
        this.approveFailure = new PaymentGatewayException(reason, providerErrorCode, message);
    }

    /** approve가 특정 상태로 성공하도록 설정한다(예: PENDING = 미확정). */
    public void stubApproveStatus(GatewayPaymentStatus status) {
        this.approveFailure = null;
        this.approveStatus = status;
    }

    /** query가 반환할 상태를 강제한다(보관된 approve 결과보다 우선). */
    public void stubQueryStatus(GatewayPaymentStatus status) {
        this.queryStatusOverride = status;
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
        approveFailure = null;
        approveStatus = GatewayPaymentStatus.PAID;
        queryStatusOverride = null;
    }
}
