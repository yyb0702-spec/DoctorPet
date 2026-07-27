package com.doctorpet.global.gateway.payment.portone;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import com.doctorpet.global.gateway.payment.support.SensitiveDataMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PortOne V2 실연동 결제 게이트웨이. {@code payment.gateway=portone}일 때만 활성화된다(운영).
 *
 * <p><b>범위 경계</b>: 이 클래스는 설정 주입·오류 분류·마스킹 로깅 구조를 갖춘 골격이다.
 * PortOne V2의 실제 엔드포인트·인증 헤더·요청/응답 JSON 필드 바인딩은 인증정보·엔드포인트가 확정되는
 * 실연동 시점(미확정 B-1)에 연결한다. 그 전까지 호출은 명확한 예외로 경계를 알린다 —
 * 추측으로 공급자 페이로드를 지어내지 않는다.
 *
 * <p>오류 코드 → 재시도 성격 매핑은 {@link PortOneErrorCodeMapper}가, 재시도 루프·상태 전이는
 * 상위 결제 서비스가 담당한다(SA §9-4). 연결·읽기 타임아웃, 최대 재시도·백오프 수치는
 * {@link PortOneProperties}로 주입된다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PortOneProperties.class)
@ConditionalOnProperty(name = "payment.gateway", havingValue = "portone")
public class PortOnePaymentGateway implements PaymentGateway {

    private final PortOneProperties properties;
    private final PortOneErrorCodeMapper errorCodeMapper;

    public PortOnePaymentGateway(PortOneProperties properties, PortOneErrorCodeMapper errorCodeMapper) {
        this.properties = properties;
        this.errorCodeMapper = errorCodeMapper;
        requireConfigured();
    }

    @Override
    public BillingKeyIssueResult verifyBillingKey(String billingKey) {
        log.debug("PortOne verifyBillingKey 요청 billingKey={}", SensitiveDataMasker.maskSecret(billingKey));
        throw integrationPending("verifyBillingKey");
    }

    @Override
    public PaymentApproveResult approve(PaymentApproveCommand command) {
        // 멱등키는 로깅해 추적하되, 빌링키 원본은 마스킹한다(보안 규칙).
        log.info("PortOne approve 요청 merchantPaymentId={} amount={} billingKey={}",
                command.merchantPaymentId(), command.amount(), SensitiveDataMasker.maskSecret(command.billingKey()));
        throw integrationPending("approve");
    }

    @Override
    public PaymentQueryResult query(String merchantPaymentId) {
        log.info("PortOne query 요청 merchantPaymentId={}", merchantPaymentId);
        throw integrationPending("query");
    }

    /**
     * 공급자 예외를 내부 실패 사유로 분류해 게이트웨이 예외로 변환한다(실연동 시 호출 지점에서 사용).
     * 공급자 코드가 없으면 HTTP 상태로 보조 분류한다.
     */
    PaymentGatewayException translate(String providerErrorCode, Integer httpStatus, String message, Throwable cause) {
        GatewayFailureReason reason = providerErrorCode != null
                ? errorCodeMapper.classify(providerErrorCode)
                : errorCodeMapper.classifyHttpStatus(httpStatus == null ? 0 : httpStatus);
        log.warn("PortOne 오류 분류 providerCode={} httpStatus={} reason={}", providerErrorCode, httpStatus, reason);
        return new PaymentGatewayException(reason, providerErrorCode, message, cause);
    }

    private void requireConfigured() {
        if (isBlank(properties.getBaseUrl())
                || isBlank(properties.getApiSecret())
                || isBlank(properties.getStoreId())) {
            throw new IllegalStateException(
                    "PortOne 실연동 설정이 없습니다. payment.portone.base-url·api-secret·store-id를 모두 주입하세요(커밋 금지).");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private UnsupportedOperationException integrationPending(String operation) {
        return new UnsupportedOperationException(
                "PortOne 실 요청/응답 바인딩 미구현: " + operation
                        + " — 엔드포인트·인증·페이로드는 실연동(미확정 B-1)에서 연결한다.");
    }
}
